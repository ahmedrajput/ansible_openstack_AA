<#list transactions as tx>
${tx.startTime?string("yyyy-MM-dd HH:mm:ss")},${(tx.senderMSISDN)!""},${(tx.receiverMSISDN)!""},<#if tx.amount??><#attempt>"${(amountUtils.formatAmount(tx.amount))}"<#recover></#attempt></#if>,${(tx.resultCode?string("0"))!""},${(tx.ersReference)},${(tx.transactionProfile)!""}
</#list>
