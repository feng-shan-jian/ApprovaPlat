package com.ruoyi.flowable.service.model;

import java.math.BigDecimal;
import java.util.regex.Pattern;
import org.flowable.common.engine.api.FlowableException;
import org.flowable.dmn.api.DecisionExecutionAuditContainer;
import org.flowable.dmn.api.ExecuteDecisionContext;
import org.flowable.dmn.engine.DmnEngineConfiguration;
import org.flowable.dmn.engine.impl.RuleEngineExecutorImpl;
import org.flowable.dmn.engine.impl.el.ELExecutionContext;
import org.flowable.dmn.engine.impl.hitpolicy.ComposeRuleResultBehavior;
import org.flowable.dmn.model.Decision;
import org.flowable.dmn.model.DecisionTable;
import org.flowable.dmn.model.HitPolicy;
import org.flowable.dmn.model.RuleInputClauseContainer;
import org.flowable.dmn.model.RuleOutputClauseContainer;

/** 仅 v2 受控决策表绕开 Flowable 的 JUEL/double 数字字面量转换；命中策略和审计仍由引擎执行。 */
public class WorkflowDecisionTableExecutor extends RuleEngineExecutorImpl
{
    public static final String EXPORTER = "ApprovaPlat Decision Table";
    private static final Pattern CONDITION = Pattern.compile("^(<=|>=|!=|<|>)? ?(.+)$");

    public WorkflowDecisionTableExecutor(DmnEngineConfiguration configuration)
    {
        super(null, null, null, configuration);
    }

    @Override
    public DecisionExecutionAuditContainer execute(Decision decision, ExecuteDecisionContext context)
    {
        if (decision == null || decision.getDmnDefinition() == null
                || !EXPORTER.equals(decision.getDmnDefinition().getExporter())
                || !"2".equals(decision.getDmnDefinition().getExporterVersion())) return super.execute(decision, context);
        if (!(decision.getExpression() instanceof DecisionTable table)
                || (table.getHitPolicy() != HitPolicy.UNIQUE && table.getHitPolicy() != HitPolicy.FIRST))
            throw new FlowableException("受控决策表仅支持 UNIQUE/FIRST 决策表");
        // 拒绝伪造 v2 的动态数字表达式，包括本次执行未命中的行。
        for (var rule : table.getRules())
        {
            for (var input : rule.getInputEntries())
                if ("number".equals(input.getInputClause().getInputExpression().getTypeRef()))
                {
                    if (!input.getInputClause().getInputExpression().getText().matches("[A-Za-z_][A-Za-z0-9_]{0,127}"))
                        throw new FlowableException("受控数值输入必须引用变量名");
                    if (!"-".equals(input.getInputEntry().getText())) bound(input.getInputEntry().getText());
                }
            for (var output : rule.getOutputEntries())
                if ("number".equals(output.getOutputClause().getTypeRef()))
                    WorkflowDecisionTableDecimal.parse(output.getOutputEntry().getText());
        }
        return new ExactLiterals().execute(decision, context);
    }

    private static BigDecimal bound(String expression)
    {
        if (expression == null) throw new FlowableException("受控数值条件不能为空");
        var matcher = CONDITION.matcher(expression);
        if (!matcher.matches()) throw new FlowableException("受控数值条件必须为单值比较");
        return WorkflowDecisionTableDecimal.parse(matcher.group(2));
    }

    private class ExactLiterals extends RuleEngineExecutorImpl
    {
        ExactLiterals()
        {
            super(WorkflowDecisionTableExecutor.this.hitPolicyBehaviors, WorkflowDecisionTableExecutor.this.expressionManager,
                    WorkflowDecisionTableExecutor.this.objectMapper, WorkflowDecisionTableExecutor.this.dmnEngineConfiguration);
        }

        @Override
        protected Boolean executeInputExpressionEvaluation(RuleInputClauseContainer input, ELExecutionContext context)
        {
            if (!"number".equals(input.getInputClause().getInputExpression().getTypeRef()))
                return super.executeInputExpressionEvaluation(input, context);
            String expression = input.getInputEntry().getText();
            Object actual = context.getStackVariables().get(input.getInputClause().getInputExpression().getText());
            if (!(actual instanceof Number)) throw new FlowableException("受控决策的数值输入必须为数值");
            int compared = new BigDecimal(actual.toString()).compareTo(bound(expression));
            var matcher = CONDITION.matcher(expression);
            matcher.matches();
            return switch (matcher.group(1) == null ? "=" : matcher.group(1)) {
                case "!=" -> compared != 0;
                case "<" -> compared < 0;
                case "<=" -> compared <= 0;
                case ">" -> compared > 0;
                case ">=" -> compared >= 0;
                default -> compared == 0;
            };
        }

        @Override
        protected void composeOutputEntryResult(int ruleNumber, RuleOutputClauseContainer output,
                HitPolicy hitPolicy, ELExecutionContext context)
        {
            if (!"number".equals(output.getOutputClause().getTypeRef()))
            {
                super.composeOutputEntryResult(ruleNumber, output, hitPolicy, context);
                return;
            }
            String key = output.getOutputClause().getName();
            BigDecimal value = WorkflowDecisionTableDecimal.parse(output.getOutputEntry().getText());
            context.getStackVariables().put(key, value);
            ((ComposeRuleResultBehavior) getHitPolicyBehavior(hitPolicy)).composeRuleResult(ruleNumber, key, value, context);
            context.getAuditContainer().addOutputEntry(ruleNumber, output.getOutputEntry().getId(), value);
            context.getAuditContainer().addDecisionResultType(key, "number");
        }
    }
}
