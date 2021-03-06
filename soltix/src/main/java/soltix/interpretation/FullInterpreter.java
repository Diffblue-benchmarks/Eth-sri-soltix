/*
 * SOLTIX - Scalable automated framework for testing Solidity compilers.
 *
 * Author: Nils Weller <nweller@uni-bremen.de>
 *
 * Copyright (C) 2018 Secure, Reliable, and Intelligent Systems Lab, ETH Zurich
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package soltix.interpretation;

import soltix.Configuration;
import soltix.ast.*;
import soltix.interpretation.expressions.Expression;
import soltix.interpretation.expressions.ExpressionEvaluationErrorHandler;
import soltix.interpretation.expressions.ExpressionEvaluator;
import soltix.interpretation.values.IntegerValue;
import soltix.interpretation.values.Value;
import soltix.interpretation.variables.Variable;
import soltix.interpretation.variables.VariableEnvironment;
import soltix.interpretation.variables.VariableValues;
import soltix.util.JSONValueConverter;
import soltix.util.RandomNumbers;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

import java.io.FileWriter;
import java.util.ArrayList;
import java.util.Stack;

// Full interpretation of transactions applied to contract functions.
//
// Unlike other IInterpreterCallback implementations, we are passed control on a per-transaction rather than
// per-node basis (therer are some ugly distinct interfaces, but ASTInterpreter is still involved for now, since it does
// some generally useful things like modifier invocation to implementation resolutions)
public class FullInterpreter implements IInterpreterCallback {
    private AST ast;
    private ASTInterpreter astInterpreter;
    private ArrayList<Transaction> transactions;
    private ExpressionEvaluator expressionEvaluator;

    public FullInterpreter(ArrayList<Transaction> transactions) {
        this.transactions = transactions;
        // TODO Supply proper error handler policy to ensure stability for preceding iterations
        expressionEvaluator = new ExpressionEvaluator(new ExpressionEvaluationErrorHandler(new RandomNumbers(Configuration.randomNumbersSeed)));
    }

    public void initialize(ASTInterpreter astInterpreter) {
        this.astInterpreter = astInterpreter;
        ast = astInterpreter.getAST();
        emittedEventsJSONObjectList = new ArrayList<JSONObject>();
    }

    public ASTInterpreter.NavigationPolicy getNavigationPolicy() {
        return ASTInterpreter.NavigationPolicy.NAVIGATION_POLICY_FULL_INTERPRETATION;
    }

    public ASTNode nextTargetStatement() throws Exception {
        throw new Exception("Invalid call to FullInterpreter.nextTargetStatement");
    }

    private ArrayList<JSONObject> emittedEventsJSONObjectList;

    //public void start(ASTContractDefinition contract, ASTFunctionDefinition function, JSONObject transaction);
    public void finish() throws Exception {
        // Write event results
        FileWriter file = new FileWriter(Configuration.interpretationOutputLogFile);

        // TODO this currently produces a single huge line, which should be pretty-printed (maybe just use a nodejs
        // script to clean up the produced file)
        for (JSONObject object : emittedEventsJSONObjectList) {
            file.write(object.toJSONString());
        }
        file.flush();
    }

    public void visitNodeBeforeProcessing(ASTNode node) throws Exception {
        throw new Exception("Invalid call to FullInterpreter.visitNodeBeforeProcessing");
    }
    public void visitNodeAfterProcessing(ASTNode node) throws Exception {
        throw new Exception("Invalid call to FullInterpreter.visitNodeBeforeProcessing");
    }

    public void run() throws Exception {
        initializeGlobalEnvironment(transactions.get(0).getContract()); // TODO multiple contracts?
        for (Transaction transaction : transactions) {
            Value result = interpretTransaction(transaction);
            // TODO use result
        }
    }


    private Stack<SolidityStackFrame> callStack = new Stack<SolidityStackFrame>();
    protected SolidityStackFrame currentStackFrame() { return callStack.peek(); }

    public Value interpretTransaction(Transaction transaction) throws Exception {
        initializeLocalEnvironment();

        // Set up initial stack frame
        SolidityStackFrame stackFrame = new SolidityStackFrame(transaction.getContract(),
                                                                transaction.getFunction(),
                                                                transaction.getArguments());
        callStack.push(stackFrame);

        ASTNode startNode = transaction.getFunction();
        ast.setCurrentNode(startNode);

        Value result = doInterpret();
        return result;
    }




    private VariableEnvironment globalEnvironment;

    protected void initializeGlobalEnvironment(ASTContractDefinition currentContract) throws Exception {
        // Prepare variable environment, which will hold a single, continuously updated value set while synthesizing
        // expressions
        globalEnvironment = new VariableEnvironment(ast,true);

        // Generate storage variables
        for (ASTNode tmp : currentContract.getVariables()) {
            ASTVariableDeclaration variableDeclaration = (ASTVariableDeclaration)tmp;

            Variable variable  = new Variable(variableDeclaration);
            VariableValues variableValues = new VariableValues(variable, 0);

            // Start out with initializer value
            variableValues.addValue(variableDeclaration.getInitializerValue());
            globalEnvironment.addVariableValues(variable, variableValues);
        }
    }

    protected void initializeLocalEnvironment() {
    }



    protected Value doInterpret() throws Exception {
        ASTNode currentNode = ast.getCurrentNode();
        Scope currentScope = currentStackFrame().getScope();

        currentNode.setCovered(true);
        currentScope.enterNode(currentNode);

        Value returnValue = null;

        if (currentNode instanceof ASTFunctionDefinition) {
            ASTBlock body = ((ASTFunctionDefinition) currentNode).getBody();
            body.setCovered(true);
            returnValue = interpretChildNodes(body);
        } else if (currentNode instanceof ASTEmitStatement) {
            interpretEmitStatement((ASTEmitStatement)currentNode);
        } else {
            throw new Exception("FullInterpreter.doInterpret for unimplemented node type " + currentNode.getClass().toString());
        }

        /*
        // Depth-first child node traversal for all paths
        for (int i = 0; i < currentNode.getChildCount(); ++i) {
            ast.setCurrentNode(currentNode.getChild(i));
            interpret(ast);
        }
*/
        // Restore position
        //ast.setCurrentNode(currentNode);
        //currentScope.leaveNode(currentNode);
        return returnValue;
    }

    // Depth-first child node traversal for all paths
    protected Value interpretChildNodes(ASTNode currentNode) throws Exception {
        Value result = null;
        for (int i = 0; i < currentNode.getChildCount(); ++i) {
            ast.setCurrentNode(currentNode.getChild(i));
            result = doInterpret();
            if (result != null) {
                // Have return value - stop
                break;
            }
        }
        return result;
    }


    protected void interpretEmitStatement(ASTEmitStatement emitStatement) throws Exception {
        JSONObject eventObject = new JSONObject();
        eventObject.put("event", emitStatement.getName());
        emittedEventsJSONObjectList.add(eventObject);

        JSONObject argsObject = new JSONObject();

        // Evaluate arguments
        ASTFunctionCall functionCall = emitStatement.getFunctionCall();
        ArrayList<Expression> arguments = functionCall.getExpressionArguments(null/*TODO*/);
        for (int i = 0 ; i < arguments.size(); ++i) {
            System.out.println("  arg " + i + " " + arguments.get(i).toASTNode().toSolidityCode());

            Value result = expressionEvaluator.evaluateForAll(globalEnvironment, arguments.get(i)).values.get(0);
            // TODO Event argument name
            argsObject.put("a", JSONValueConverter.objsoltixromValue(result));
        }

        eventObject.put("args", argsObject);
    }
}
