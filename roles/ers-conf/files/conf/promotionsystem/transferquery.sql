SELECT
t.senderResellerId as resellerId,
sum(t.totalAmount) as sentAmountOverPeriod,
sum(t.totalTransactionCount) AS transactionCountOverPeriod
FROM
promotionsystsem_transaction_statistics_aggregation as t
<#if campaign.activationStartDate?? && campaign.activationEndDate??>LEFT JOIN promotionsystem_reseller_activations as r ON (t.senderResellerId = r.resellerId)</#if>
WHERE
t.aggregationDate BETWEEN ? AND ?
<#if campaignRule.channel??> AND t.channel = '${campaignRule.channel}' </#if>
<#if campaignRule.targetTransactionType?? && campaignRule.targetTransactionType!="ALL" > AND t.productId = '${campaignRule.targetTransactionType}' <#else>AND t.productId != '${campaignRule.targetTransactionType}'</#if>
AND t.eachTransactionAmount >= ${campaignRule.minimumTransactionValue}
AND t.receiverResellerLevel = ${campaignRule.receiverResellerLevel}
AND t.senderResellerLevel = ${campaignRule.senderResellerLevel}
<#if campaignRule.senderRegion??>AND t.senderRegion = '${campaignRule.senderRegion}'</#if>
<#if campaignRule.receiverRegion??>AND t.receiverRegion = '${campaignRule.receiverRegion}'</#if>
<#if campaign.activationStartDate?? && campaign.activationEndDate??>AND r.activationDate BETWEEN str_to_date('${campaign.activationStartDate}','%b %d, %Y %r') AND str_to_date('${campaign.activationEndDate}','%b %d, %Y %r')</#if> 
group by t.senderResellerId