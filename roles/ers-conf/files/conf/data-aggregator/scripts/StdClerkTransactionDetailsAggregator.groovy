package se.seamless.ers.components.dataaggregator.aggregator

import groovy.time.TimeCategory
import groovy.transform.EqualsAndHashCode
import groovy.transform.ToString
import groovy.util.logging.Log4j
import org.springframework.jdbc.core.PreparedStatementSetter
import java.util.concurrent.TimeUnit
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.BatchPreparedStatementSetter
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.transaction.annotation.Transactional
import com.seamless.ers.interfaces.platform.clients.transaction.model.ERSTransactionResultCode

/**
 * @author Sharique
 */
@Log4j
@DynamicMixin
public class StdClerkTransactionDetailsAggregator extends AbstractAggregator {
    static final def TABLE = "std_clerk_transaction_details_aggregator"
    static final def MONETARY_CATEGORIES = [
            "TOPUP",
            "PRODUCT_RECHARGE",
            "CREDIT_TRANSFER",
            "REVERSE_CREDIT_TRANSFER",
            "VOUCHER_PURCHASE",
            "VOS_PURCHASE",
            "VOT_PURCHASE",
            "PURCHASE"
    ]

     static final def VOUCHER_CATEGORIES = [
          
            "VOUCHER_PURCHASE",
            "VOS_PURCHASE",
            "VOT_PURCHASE",
            "PURCHASE"
    ]
    @Value('${StdClerkTransactionDetailsAggregator.batch:1000}')
    int limit
    @EqualsAndHashCode
    @ToString
    private static class Key {
        Date date
        String productSKU
        String productName
        String resellerId
        String user
    }

    @Transactional
    @Scheduled(cron = '${StdClerkTransactionDetailsAggregator.cron:0 0/30 * * * ?}')
    public void aggregate() {
    	def transactions = getTransactions(limit)
    	if (transactions) {
    		def matchedTransactions = findAllowedProfiles(transactions, MONETARY_CATEGORIES).
    		findAll(successfulTransactions)
    		def aggregation = matchedTransactions.collect(transactionInfo).findAll().groupBy
    		{
    			new Key(date: it.date,resellerId: it.senderResellerID, user: it.user, productSKU: it.productSKU, productName : it.productName
    			)
    		}

    		.collect() { key, list ->
    			[key : key,  amount:  list.sum { it.amount }]
    			}
    		
    		updateAggregation(aggregation)
    		updateCursor(transactions)
    		schedule()
    	}
    }

    private def transactionInfo =
            {
                log.debug("Processing transaction StdClerkTransactionDetailsAggregator: ${it}")
                def tr = parser.parse(it.getJSON()).getAsJsonObject()
                log.debug("***************transaction StdClerkTransactionDetailsAggregator :" + tr + " **************")
                def productSKU = tr.get("transactionProperties")?.getAsJsonObject()?.get("map")?.getAsJsonObject()?.get("productSKU")?.getAsString()
                if(productSKU == null)
                    productSKU = ""
	            def productName = tr.get("transactionProperties")?.getAsJsonObject()?.get("map")?.getAsJsonObject()?.get("productName")?.getAsString()
                if(productName == null)
		            productName = ""
		            	
                def user = tr.get("senderPrincipal")?.getAsJsonObject()?.get("user")?.getAsJsonObject()?.get("userId")?.getAsString()
		        if (!user)
		            user = " "
                def profile = asStringOrDefault(parser.parse(it.getJSON()).getAsJsonObject(), "profileId")
		if (profile == "CREDIT_TRANSFER") {
               		productName="CREDIT_TRANSFER"
			productSKU="CREDIT_TRANSFER"
		} else if(profile == "REVERSE_CREDIT_TRANSFER"){
            productName="REVERSE_CREDIT_TRANSFER"
                productSKU="REVERSE_CREDIT_TRANSFER"
		}

		def senderPrincipalJsonObject = tr.get("senderPrincipal");
                def senderResellerID= ""
                if(senderPrincipalJsonObject) {
                    senderResellerID = asString(senderPrincipalJsonObject, "resellerId")
                } else if (tr.get("principal")) {
                    senderPrincipalJsonObject = tr.get("principal")?.getAsJsonObject()
                    senderResellerID = asString(senderPrincipalJsonObject, "resellerId")
                }

                def date = it.getEndTime().clone().clearTime()
                def amount = collectAmount(tr,it.profile)
                
        if (profile == "REVERSE_CREDIT_TRANSFER")
        {
        	if (amount && amount < 0)
            {
        		amount = amount.negate()
            }
        }
		
            if(VOUCHER_CATEGORIES.contains(profile)){
            	tr.get("transactionRows")?.getAsJsonArray()?.iterator().collect {
                log.debug("process transaction row:" + it)
                amount = getBigDecimalFieldFromAnyField(it,"value","amount")
                productName = getProductName(tr)
                user = tr.get("principal")?.getAsJsonObject()?.get("user")?.getAsJsonObject()?.get("userId")?.getAsString()

            	    }
		} 
                [date : date, senderResellerID: senderResellerID, user: user, productSKU : productSKU, productName : productName, amount:amount]

            }

 def getProductName =
            {	tr ->
                tr.get("purchasedProducts")?.getAsJsonArray()?.iterator().collect
                {

                    it.get("product")?.getAsJsonObject()?.get("name")?.getAsString()

                }
                .find {it}
            }

    private def updateAggregation(List aggregation) {

        if (aggregation) {
            def date = new Date()
            use(TimeCategory)
                    {
                        date = toSqlDate(date)
                    }
            def sql = "INSERT INTO ${TABLE} (date,resellerId, user,productSKU,productName,amount) values (?,?,?,?,?,?) ON DUPLICATE KEY UPDATE amount=amount+VALUES(amount)"
            def batchUpdate = jdbcTemplate.batchUpdate(sql, [
                    setValues   :
                            { ps, i ->
                                ps.setDate(1, toSqlDate(aggregation[i].key.date))
                                ps.setString(2, aggregation[i].key.resellerId)
                                ps.setString(3, aggregation[i].key.user)
                                ps.setString(4, aggregation[i].key.productSKU)
                                ps.setString(5, aggregation[i].key.productName)
                                ps.setDouble(6, aggregation[i].amount)
                            },
                    getBatchSize:
                            { aggregation.size() }
            ] as BatchPreparedStatementSetter)
        }
    }
}
