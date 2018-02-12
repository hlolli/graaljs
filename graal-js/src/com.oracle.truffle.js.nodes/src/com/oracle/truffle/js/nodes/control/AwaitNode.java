/*
 * Copyright (c) 2017, 2018, Oracle and/or its affiliates. All rights reserved.
 * ORACLE PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 */
package com.oracle.truffle.js.nodes.control;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.frame.MaterializedFrame;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.object.DynamicObject;
import com.oracle.truffle.api.object.HiddenKey;
import com.oracle.truffle.js.nodes.JavaScriptNode;
import com.oracle.truffle.js.nodes.access.JSReadFrameSlotNode;
import com.oracle.truffle.js.nodes.access.PropertyGetNode;
import com.oracle.truffle.js.nodes.access.PropertySetNode;
import com.oracle.truffle.js.nodes.arguments.AccessIndexedArgumentNode;
import com.oracle.truffle.js.nodes.function.JSFunctionCallNode;
import com.oracle.truffle.js.runtime.JSArguments;
import com.oracle.truffle.js.runtime.JSContext;
import com.oracle.truffle.js.runtime.JSFrameUtil;
import com.oracle.truffle.js.runtime.JavaScriptRootNode;
import com.oracle.truffle.js.runtime.UserScriptException;
import com.oracle.truffle.js.runtime.builtins.JSFunction;
import com.oracle.truffle.js.runtime.builtins.JSFunctionData;
import com.oracle.truffle.js.runtime.builtins.JSPromise;
import com.oracle.truffle.js.runtime.objects.Completion;
import com.oracle.truffle.js.runtime.objects.Undefined;

public class AwaitNode extends JavaScriptNode implements ResumableNode, SuspendNode {

    @Child protected JavaScriptNode expression;
    @Child protected JSReadFrameSlotNode readAsyncResultNode;
    @Child protected JSReadFrameSlotNode readAsyncContextNode;
    @Child protected JSFunctionCallNode awaitTrampolineCall;
    @Child private JSFunctionCallNode createPromiseCapability;
    @Child private PropertyGetNode getPromiseResolve;
    @Child private JSFunctionCallNode callPromiseResolveNode;
    @Child private JSFunctionCallNode callPerformPromiseThen;
    @Child private PropertyGetNode getPromise;
    @Child private PropertySetNode setPromiseIsHandled;
    @Child private PropertySetNode setAsyncContext;
    @Child private PropertySetNode setAsyncTarget;
    @Child private PropertySetNode setAsyncGenerator;
    protected final JSContext context;

    static final HiddenKey ASYNC_CONTEXT = new HiddenKey("AsyncContext");
    static final HiddenKey ASYNC_TARGET = new HiddenKey("AsyncTarget");
    static final HiddenKey ASYNC_GENERATOR = new HiddenKey("AsyncGenerator");

    protected AwaitNode(JSContext context, JavaScriptNode expression, JSReadFrameSlotNode readAsyncContextNode, JSReadFrameSlotNode readAsyncResultNode) {
        this.context = context;
        this.expression = expression;
        this.readAsyncResultNode = readAsyncResultNode;
        this.readAsyncContextNode = readAsyncContextNode;
        this.awaitTrampolineCall = JSFunctionCallNode.createCall();

        this.getPromiseResolve = PropertyGetNode.create("resolve", false, context);
        this.callPromiseResolveNode = JSFunctionCallNode.createCall();
        this.getPromise = PropertyGetNode.create("promise", false, context);
        this.setPromiseIsHandled = PropertySetNode.create(JSPromise.PROMISE_IS_HANDLED, false, context, false);
        this.setAsyncContext = PropertySetNode.create(ASYNC_CONTEXT, false, context, false);
        this.setAsyncTarget = PropertySetNode.create(ASYNC_TARGET, false, context, false);
        this.setAsyncGenerator = PropertySetNode.create(ASYNC_GENERATOR, false, context, false);
        this.createPromiseCapability = JSFunctionCallNode.createCall();
        this.callPerformPromiseThen = JSFunctionCallNode.createCall();
    }

    public static AwaitNode create(JSContext context, JavaScriptNode expression, JSReadFrameSlotNode readAsyncContextNode, JSReadFrameSlotNode readAsyncResultNode) {
        return new AwaitNode(context, expression, readAsyncContextNode, readAsyncResultNode);
    }

    @Override
    public Object execute(VirtualFrame frame) {
        Object value = expression.execute(frame);
        return suspendAwait(frame, value);
    }

    protected final Object suspendAwait(VirtualFrame frame, Object value) {
        Object[] initialState = (Object[]) readAsyncContextNode.execute(frame);
        CallTarget resumeTarget = (CallTarget) initialState[0];
        DynamicObject generator = (DynamicObject) initialState[1];
        MaterializedFrame asyncContext = (MaterializedFrame) initialState[2];

        if (JSPromise.isJSPromise(generator)) {
            DynamicObject currentCapability = generator;
            Object parentPromise = getPromise.getValue(currentCapability);
            context.notifyPromiseHook(-1 /* parent info */, (DynamicObject) parentPromise);
        }

        DynamicObject promiseCapability = newPromiseCapability();
        Object resolve = getPromiseResolve.getValue(promiseCapability);
        callPromiseResolveNode.executeCall(JSArguments.createOneArg(Undefined.instance, resolve, value));
        DynamicObject onFulfilled = createAwaitFulfilledFunction(resumeTarget, asyncContext, generator);
        DynamicObject onRejected = createAwaitRejectedFunction(resumeTarget, asyncContext, generator);
        DynamicObject throwawayCapability = newPromiseCapability();
        setPromiseIsHandled.setValueBoolean(getPromise.getValue(throwawayCapability), true);

        DynamicObject promise = (DynamicObject) getPromise.getValue(promiseCapability);
        context.notifyPromiseHook(-1 /* parent info */, promise);
        performPromiseThen(promise, onFulfilled, onRejected, throwawayCapability);
        throw YieldException.AWAIT_NULL; // value is ignored
    }

    @Override
    public Object resume(VirtualFrame frame) {
        int index = getStateAsInt(frame);
        if (index == 0) {
            Object value = expression.execute(frame);
            setState(frame, 1);
            return suspendAwait(frame, value);
        } else {
            setState(frame, 0);
            return resumeAwait(frame);
        }
    }

    protected Object resumeAwait(VirtualFrame frame) {
        // We have been restored at this point. The frame contains the resumption state.
        Completion result = (Completion) readAsyncResultNode.execute(frame);
        if (result.isNormal()) {
            return result.getValue();
        } else {
            assert result.isThrow();
            Object reason = result.getValue();
            throw UserScriptException.create(reason, this);
        }
    }

    private DynamicObject newPromiseCapability() {
        return (DynamicObject) createPromiseCapability.executeCall(JSArguments.createZeroArg(Undefined.instance, context.getAsyncFunctionPromiseCapabilityConstructor()));
    }

    private void performPromiseThen(Object promise, DynamicObject onFulfilled, DynamicObject onRejected, DynamicObject resultCapability) {
        callPerformPromiseThen.executeCall(JSArguments.create(Undefined.instance, context.getPerformPromiseThen(), promise, onFulfilled, onRejected, resultCapability));
    }

    private DynamicObject createAwaitFulfilledFunction(CallTarget resumeTarget, MaterializedFrame asyncContext, Object generator) {
        JSFunctionData functionData = context.getOrCreateBuiltinFunctionData(JSContext.BuiltinFunctionKey.AwaitFulfilled, (c) -> createAwaitFulfilledImpl(c));
        DynamicObject function = JSFunction.create(context.getRealm(), functionData);
        setAsyncTarget.setValue(function, resumeTarget);
        setAsyncContext.setValue(function, asyncContext);
        setAsyncGenerator.setValue(function, generator);
        return function;
    }

    private static JSFunctionData createAwaitFulfilledImpl(JSContext context) {
        CallTarget callTarget = Truffle.getRuntime().createCallTarget(new JavaScriptRootNode() {
            @Child private JavaScriptNode valueNode = AccessIndexedArgumentNode.create(0);
            @Child private PropertyGetNode getAsyncTarget = PropertyGetNode.create(ASYNC_TARGET, false, context);
            @Child private PropertyGetNode getAsyncContext = PropertyGetNode.create(ASYNC_CONTEXT, false, context);
            @Child private PropertyGetNode getAsyncGenerator = PropertyGetNode.create(ASYNC_GENERATOR, false, context);
            @Child private AwaitResumeNode awaitResumeNode = AwaitResumeNode.create(false);

            @Override
            public Object execute(VirtualFrame frame) {
                DynamicObject functionObject = JSFrameUtil.getFunctionObject(frame);
                CallTarget asyncTarget = (CallTarget) getAsyncTarget.getValue(functionObject);
                Object asyncContext = getAsyncContext.getValue(functionObject);
                Object generator = getAsyncGenerator.getValue(functionObject);
                Object value = valueNode.execute(frame);
                return awaitResumeNode.execute(asyncTarget, asyncContext, generator, value);
            }
        });
        return JSFunctionData.createCallOnly(context, callTarget, 1, "Await Fulfilled");
    }

    private DynamicObject createAwaitRejectedFunction(CallTarget resumeTarget, MaterializedFrame asyncContext, Object generator) {
        JSFunctionData functionData = context.getOrCreateBuiltinFunctionData(JSContext.BuiltinFunctionKey.AwaitRejected, (c) -> createAwaitRejectedImpl(c));
        DynamicObject function = JSFunction.create(context.getRealm(), functionData);
        setAsyncTarget.setValue(function, resumeTarget);
        setAsyncContext.setValue(function, asyncContext);
        setAsyncGenerator.setValue(function, generator);
        return function;
    }

    private static JSFunctionData createAwaitRejectedImpl(JSContext context) {
        CallTarget callTarget = Truffle.getRuntime().createCallTarget(new JavaScriptRootNode() {
            @Child private JavaScriptNode reasonNode = AccessIndexedArgumentNode.create(0);
            @Child private PropertyGetNode getAsyncTarget = PropertyGetNode.create(ASYNC_TARGET, false, context);
            @Child private PropertyGetNode getAsyncContext = PropertyGetNode.create(ASYNC_CONTEXT, false, context);
            @Child private PropertyGetNode getAsyncGenerator = PropertyGetNode.create(ASYNC_GENERATOR, false, context);
            @Child private AwaitResumeNode awaitResumeNode = AwaitResumeNode.create(true);

            @Override
            public Object execute(VirtualFrame frame) {
                DynamicObject functionObject = JSFrameUtil.getFunctionObject(frame);
                CallTarget asyncTarget = (CallTarget) getAsyncTarget.getValue(functionObject);
                Object asyncContext = getAsyncContext.getValue(functionObject);
                Object generator = getAsyncGenerator.getValue(functionObject);
                Object reason = reasonNode.execute(frame);
                return awaitResumeNode.execute(asyncTarget, asyncContext, generator, reason);
            }
        });
        return JSFunctionData.createCallOnly(context, callTarget, 1, "Await Rejected");
    }

    @Override
    protected JavaScriptNode copyUninitialized() {
        JavaScriptNode expressionCopy = cloneUninitialized(expression);
        JSReadFrameSlotNode asyncResultCopy = cloneUninitialized(readAsyncResultNode);
        JSReadFrameSlotNode asyncContextCopy = cloneUninitialized(readAsyncContextNode);
        return create(context, expressionCopy, asyncContextCopy, asyncResultCopy);
    }
}
