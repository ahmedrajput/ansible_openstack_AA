<#if language?? && language == "fr">
<#if transactions ??>
<#list transactions as tx>
${tx.startTime?string("yyyy-MM-dd HH:mm:ss")};${tx.typeName};<#if tx.amount??>${(amountUtils.formatAmount(tx.amount))}</#if>;${(tx.receiverMSISDN)!""}
</#list>
<#else>
Aucune transaction trouve.
</#if>
<#else>
<#if transactions ??>
<#list transactions as tx>
${tx.startTime?string("yyyy-MM-dd HH:mm:ss")};${tx.typeName};<#if tx.amount??>${(amountUtils.formatAmount(tx.amount))}</#if>;${(tx.receiverMSISDN)!""}
</#list>
<#else>
No transaction found.
</#if>
</#if>