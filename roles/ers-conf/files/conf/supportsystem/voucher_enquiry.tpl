<#assign limit = 5>
<#if fields??><#if fields.LIMIT??><#assign limit = fields.LIMIT?number></#if></#if>
<#assign count = 1>
<#if transactions ??>
	<#list transactions as tx>
	<#if tx.purchasedProducts??>
		<#list tx.purchasedProducts as purchasedProduct>
			<#list purchasedProduct.rows as row>
${row.properties.PIN}; ${row.expiryDate?string("yyyy/MM/dd")}; ${purchasedProduct.product.itemPrice.value}
				<#assign count = count + 1>
				<#if (count > limit)><#break></#if>
			</#list>
			<#if (count > limit)><#break></#if>
		</#list>
		<#if (count > limit)><#break></#if>
	</#if>
	</#list>
</#if>