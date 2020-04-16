/*
 * Copyright (c) 2020, 2020, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * The Universal Permissive License (UPL), Version 1.0
 *
 * Subject to the condition set forth below, permission is hereby granted to any
 * person obtaining a copy of this software, associated documentation and/or
 * data (collectively the "Software"), free of charge and under any and all
 * copyright rights in the Software, and any and all patent rights owned or
 * freely licensable by each licensor hereunder covering either (i) the
 * unmodified Software as contributed to or provided by such licensor, or (ii)
 * the Larger Works (as defined below), to deal in both
 *
 * (a) the Software, and
 *
 * (b) any piece of software and/or hardware listed in the lrgrwrks.txt file if
 * one is included with the Software each a "Larger Work" to which the Software
 * is contributed by such licensors),
 *
 * without restriction, including without limitation the rights to copy, create
 * derivative works of, display, perform, and distribute the Software and make,
 * use, sell, offer for sale, import, export, have made, and have sold the
 * Software and the Larger Work(s), and to sublicense the foregoing rights on
 * either these or other terms.
 *
 * This license is subject to the following condition:
 *
 * The above copyright notice and either this complete permission notice or at a
 * minimum a reference to the UPL must be included in all copies or substantial
 * portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.oracle.truffle.js.nodes.intl;

import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.js.nodes.JavaScriptBaseNode;
import com.oracle.truffle.js.nodes.access.PropertyGetNode;
import com.oracle.truffle.js.runtime.JSContext;
import com.oracle.truffle.js.runtime.builtins.JSNumberFormat;
import com.oracle.truffle.js.runtime.objects.Undefined;
import com.oracle.truffle.js.runtime.util.IntlUtil;

/**
 * SetNumberFormatDigitOptions() operation.
 */
public abstract class SetNumberFormatDigitOptionsNode extends JavaScriptBaseNode {
    @Child GetNumberOptionNode getMinIntDigitsOption;
    @Child GetNumberOptionNode getMinFracDigitsOption;
    @Child GetNumberOptionNode getMaxFracDigitsOption;
    @Child PropertyGetNode getMinSignificantDigitsOption;
    @Child PropertyGetNode getMaxSignificantDigitsOption;
    @Child DefaultNumberOptionNode getMnsdDNO;
    @Child DefaultNumberOptionNode getMxsdDNO;

    protected SetNumberFormatDigitOptionsNode(JSContext context) {
        this.getMinIntDigitsOption = GetNumberOptionNode.create(context, IntlUtil.MINIMUM_INTEGER_DIGITS);
        this.getMinFracDigitsOption = GetNumberOptionNode.create(context, IntlUtil.MINIMUM_FRACTION_DIGITS);
        this.getMaxFracDigitsOption = GetNumberOptionNode.create(context, IntlUtil.MAXIMUM_FRACTION_DIGITS);
        this.getMinSignificantDigitsOption = PropertyGetNode.create(IntlUtil.MINIMUM_SIGNIFICANT_DIGITS, context);
        this.getMaxSignificantDigitsOption = PropertyGetNode.create(IntlUtil.MAXIMUM_SIGNIFICANT_DIGITS, context);
        this.getMnsdDNO = DefaultNumberOptionNode.create();
        this.getMxsdDNO = DefaultNumberOptionNode.create();
    }

    public static SetNumberFormatDigitOptionsNode create(JSContext context) {
        return SetNumberFormatDigitOptionsNodeGen.create(context);
    }

    public abstract Object execute(JSNumberFormat.BasicInternalState intlObj, Object options, int mnfdDefault, int mxfdDefault);

    @Specialization
    public Object setNumberFormatDigitOptions(JSNumberFormat.BasicInternalState intlObj, Object options, int mnfdDefault, int mxfdDefault) {
        int mnid = getMinIntDigitsOption.executeInt(options, 1, 21, 1);
        int mnfd = getMinFracDigitsOption.executeInt(options, 0, 20, mnfdDefault);
        int mxfdActualDefault = Math.max(mnfd, mxfdDefault);
        int mxfd = getMaxFracDigitsOption.executeInt(options, mnfd, 20, mxfdActualDefault);
        intlObj.setIntegerAndFractionsDigits(mnid, mnfd, mxfd);
        Object mnsdValue = getMinSignificantDigitsOption.getValue(options);
        Object mxsdValue = getMaxSignificantDigitsOption.getValue(options);
        if (mnsdValue != Undefined.instance || mxsdValue != Undefined.instance) {
            int mnsd = getMnsdDNO.executeInt(mnsdValue, 1, 21, 1);
            int mxsd = getMxsdDNO.executeInt(mxsdValue, mnsd, 21, 21);
            intlObj.setSignificantDigits(mnsd, mxsd);
        }
        return Undefined.instance;
    }

}
