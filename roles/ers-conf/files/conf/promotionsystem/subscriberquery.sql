 SELECT s_r.resellerId,
        -sum(s_td.amountValue) AS sentAmountOverPeriod, 
         sum(if(tp.transactionProfile='REVERSE_TOPUP',-1,t.transactionCount)) AS transactionCountOverPeriod 
FROM transactions t  
JOIN transaction_details s_td USING(transactionId) 
JOIN accounts s_ac USING(accountId) 
JOIN resellers s_r USING(resellerKey) 
JOIN transaction_profiles tp USING(transactionProfileKey) 
<#if campaignRule.targetTransactionType?? && campaignRule.targetTransactionType != "ALL">JOIN products p USING(productKey)</#if>
<#if campaign.activationStartDate?? && campaign.activationEndDate??>LEFT JOIN reseller_activations r_a ON(s_r.resellerId=r_a.resellerId)</#if>
JOIN transaction_details r_td ON(r_td.transactionId = t.transactionId) 
JOIN accounts r_ac ON(r_ac.accountId = r_td.accountId) 
JOIN resellers r_r ON(r_r.resellerKey = r_td.resellerKey) 
WHERE  
        s_ac.accountName LIKE '%_CREDIT' 
        AND tp.transactionProfile IN ('TOPUP', 'REVERSE_TOPUP') 
        AND r_ac.accountName LIKE '%OPERATOR%_DEBIT' 
        AND t.transactionDate BETWEEN ? AND ? 
        <#if campaignRule.channel??> AND t.channel = '${campaignRule.channel}' </#if> 
        AND ABS(s_td.amountValue/t.transactionCount) >= ${campaignRule.minimumTransactionValue} 
        AND r_r.resellerLevel = 0 
        <#if campaignRule.targetTransactionType?? && campaignRule.targetTransactionType != "ALL">AND p.productId like '${campaignRule.targetTransactionType}'</#if>
        <#if campaignRule.senderRegion??>AND s_td.regionId = '${campaignRule.senderRegion}'</#if>
        <#if campaignRule.receiverRegion??>AND r_td.regionId = '${campaignRule.receiverRegion}'</#if>
        <#if campaign.activationStartDate?? && campaign.activationEndDate??>AND r_a.activationDate BETWEEN str_to_date('${campaign.activationStartDate}','%b %d, %Y %r') AND str_to_date('${campaign.activationEndDate}','%b %d, %Y %r')</#if>
        group  by s_r.resellerId