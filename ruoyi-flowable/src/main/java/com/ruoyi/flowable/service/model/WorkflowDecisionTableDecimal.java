package com.ruoyi.flowable.service.model;

import java.math.BigDecimal;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import tools.jackson.databind.JsonNode;

/** 受控决策表的十进制契约，供作者输入、重叠检查和引擎字面量使用。 */
public final class WorkflowDecisionTableDecimal
{
    private WorkflowDecisionTableDecimal() { }

    public static BigDecimal read(JsonNode value)
    {
        if (value == null || (!value.isNumber() && !value.isTextual())) throw invalid("必须填写有效数值");
        return parse(value.isTextual() ? value.textValue() : value.toString());
    }

    public static BigDecimal parse(String decimal)
    {
        if (decimal == null || decimal.length() > 128
                || !decimal.matches("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?"))
            throw invalid("必须填写有效十进制数值");
        try
        {
            BigDecimal number = new BigDecimal(decimal);
            if (number.precision() > 18 || Math.abs((long) number.scale()) > 8)
                throw invalid("数值精度或范围超过支持上限");
            return number;
        }
        catch (NumberFormatException exception) { throw invalid("数值精度或范围超过支持上限"); }
    }

    private static ServiceException invalid(String message) { return new ServiceException(message, HttpStatus.BAD_REQUEST); }
}
