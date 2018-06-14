<#if language?? && language == "en">
	<#if transactions ??>
	<#list transactions as tx>
 		The transaction ${(tx.ersReference)!""} <#if tx.resultCode =0>was successfully done<#else>failed</#if>
	</#list>

	<#if transactions?size == 0>
	No transaction found that belongs to you.
	</#if>
	<#else>
	No transaction found.
	</#if>
<#elseif language?? && language == "fr">
	<#if transactions ??>
	<#list transactions as tx>
 		Le transaction ${(tx.ersReference)!""} <#if tx.resultCode =0>was successfully done<#else>failed</#if>
	</#list>

	<#if transactions?size == 0>
	No transaction found that belongs to you.
	</#if>
	<#else>
	No transaction found.
	</#if>
<#else>
    This report is not available in your language.
</#if>