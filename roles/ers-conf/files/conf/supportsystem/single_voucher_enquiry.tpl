<#assign voucherPin = "">
<#assign pinPresent = 0>
<#if fields??><#if fields.VOUCHERPIN??><#assign voucherPin = fields.VOUCHERPIN?string></#if></#if>
<#if voucherPin?string?matches('')>
Invalid format of voucher code.
<#else>
	<#if transactions ??>
		<#list transactions as tx>
			<#if tx.purchasedProducts??>
				<#list tx.purchasedProducts as purchasedProduct>
					<#list purchasedProduct.rows as row>
						<#if voucherPin?string?matches(row.properties.PIN?string)>
						<#if (toDate?datetime > row.expiryDate?datetime )>
Ø§Ù„ÙƒØ±Øª ÙŠÙ†ØªÙ‡ÙŠ ÙÙŠ  ${row.expiryDate?string("dd/MM/yyyy")}, ÙŠØ±Ø¬Ù‰ Ø§Ø¯Ø®Ø§Ù„ Ø¨ÙŠØ§Ù†Ø§Øª ÙƒØ±Øª Ù…ØªØ§Ø­ .
						<#else>
Ø±Ù‚Ù… Ø§Ù„Ø´Ø­Ù† Ø§Ù„Ù…Ø±Ø³Ù„ Ø¥Ù„ÙŠÙƒ ÙŠØ­ØªÙˆÙŠ Ø¹Ù„Ù‰ Ù…Ø¨Ù„Øº ÙˆÙ‚Ø¯Ø±Ø©  ${amountUtils.formatAmount(purchasedProduct.product.itemPrice)} ØµØ§Ù„Ø­ Ø­ØªÙ‰ ØªØ§Ø±ÙŠØ® ${row.expiryDate?string("dd/MM/yyyy")}. ÙˆÙ„Ø¥Ø¹Ø§Ø¯Ø© Ø´Ø­Ù† Ø®Ø·Ùƒ Ø§ØªØµÙ„ Ù…Ù† Ø§Ù„ÙŠØ³Ø§Ø± Ù„Ù„ÙŠÙ…Ù† Ø¨Ù€ .
<#if tx.fields?? && tx.fields.receiverAccountLinkTypeId?? && tx.fields.receiverAccountLinkTypeId="PREPAID">*334*<#else>*334*</#if>${row.properties.PIN}#
						</#if>
						<#assign pinPresent = 1>
						</#if>
					</#list>
					<#if (pinPresent == 1)><#break></#if>
				</#list>
				<#if (pinPresent == 1)><#break></#if>
			</#if>
			<#if (pinPresent == 1)><#break></#if>
		</#list>
	</#if>
</#if>