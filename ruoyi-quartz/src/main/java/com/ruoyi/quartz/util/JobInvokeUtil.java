package com.ruoyi.quartz.util;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import com.ruoyi.common.constant.Constants;
import com.ruoyi.common.utils.spring.SpringUtils;
import com.ruoyi.quartz.domain.SysJob;

/** 解析并调用允许的任务方法；参数只作为字面量。 */
public class JobInvokeUtil
{
    private static final String IDENTIFIER = "[\\p{javaJavaIdentifierStart}][\\p{javaJavaIdentifierPart}]*";
    private static final Pattern INVOCATION = Pattern.compile(
            "([^(]+)\\.(" + IDENTIFIER + ")\\((.*)\\)", Pattern.DOTALL);
    private static final Pattern PARAMETER_SEPARATOR = Pattern.compile(
            ",(?=([^\"']*[\"'][^\"']*[\"'])*[^\"']*$)");

    /** 保存时解析目标和方法，但不构造类实例或执行任务。 */
    public static boolean isValidInvokeTarget(String invokeTarget)
    {
        try
        {
            resolve(invokeTarget);
            return true;
        }
        catch (ReflectiveOperationException | IllegalArgumentException | IllegalStateException e)
        {
            return false;
        }
    }

    /** 每次执行均重新授权，覆盖已存作业及直接调度入口。 */
    public static void invokeMethod(SysJob sysJob) throws Exception
    {
        Invocation invocation = resolve(sysJob.getInvokeTarget());
        Object target = invocation.bean() != null ? invocation.bean()
                : invocation.targetType().getConstructor().newInstance();
        invocation.method().invoke(target, invocation.arguments());
    }

    private static Invocation resolve(String invokeTarget) throws ReflectiveOperationException
    {
        Matcher matcher = INVOCATION.matcher(invokeTarget == null ? "" : invokeTarget);
        if (!matcher.matches())
        {
            throw new IllegalArgumentException("任务调用格式不正确");
        }
        String targetName = matcher.group(1);
        Object[] arguments = parseArguments(matcher.group(3));
        Object bean = null;
        Class<?> targetType;
        if (targetName.chars().filter(character -> character == '.').count() > 1)
        {
            // 加载时不初始化类；授权及方法检查通过后才允许构造实例。
            targetType = Class.forName(targetName, false, JobInvokeUtil.class.getClassLoader());
        }
        else
        {
            try
            {
                bean = SpringUtils.getBean(targetName);
            }
            catch (NoSuchBeanDefinitionException e)
            {
                if (!targetName.equals(e.getBeanName())) throw e;
                throw new IllegalArgumentException("任务 Bean 不存在", e);
            }
            targetType = AopProxyUtils.ultimateTargetClass(bean);
        }
        requireTaskType(targetType);
        Method method = targetType.getMethod(matcher.group(2),
                Arrays.stream(arguments).map(Object::getClass).toArray(Class<?>[]::new));
        // 允许任务包中的继承方法，不开放 Object 或外部父类的方法。
        requireTaskType(method.getDeclaringClass());
        if (bean == null)
        {
            if (Modifier.isAbstract(targetType.getModifiers()))
            {
                throw new IllegalArgumentException("任务类不能为抽象类");
            }
            targetType.getConstructor();
        }
        else
        {
            // 授权真实类型，调用仍经过原代理，保留事务及其他切面。
            method = AopUtils.selectInvocableMethod(method, bean.getClass());
        }
        return new Invocation(targetType, bean, method, arguments);
    }

    private static void requireTaskType(Class<?> type)
    {
        String packageName = type.getPackageName();
        for (String allowedPackage : Constants.JOB_WHITELIST_STR)
        {
            if (packageName.equals(allowedPackage) || packageName.startsWith(allowedPackage + "."))
            {
                return;
            }
        }
        throw new IllegalArgumentException("任务目标不在允许的包内");
    }

    private static Object[] parseArguments(String source)
    {
        if (source.isEmpty())
        {
            return new Object[0];
        }
        List<Object> arguments = new ArrayList<>();
        // 沿用原有逗号分隔规则；不把 O'Brien 等内容中的引号改成新的转义语法。
        for (String parameter : PARAMETER_SEPARATOR.split(source, -1))
        {
            arguments.add(parseLiteral(parameter.trim()));
        }
        return arguments.toArray();
    }

    private static Object parseLiteral(String value)
    {
        if (value.startsWith("'") || value.startsWith("\""))
        {
            char quote = value.charAt(0);
            if (value.length() < 2 || value.charAt(value.length() - 1) != quote)
            {
                throw new IllegalArgumentException("任务字符串参数格式不正确");
            }
            return value.substring(1, value.length() - 1);
        }
        if ("true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value))
        {
            return Boolean.valueOf(value);
        }
        if (value.endsWith("L"))
        {
            return Long.valueOf(value.substring(0, value.length() - 1));
        }
        if (value.endsWith("D"))
        {
            return Double.valueOf(value.substring(0, value.length() - 1));
        }
        return Integer.valueOf(value);
    }

    private record Invocation(Class<?> targetType, Object bean, Method method, Object[] arguments) { }
}
