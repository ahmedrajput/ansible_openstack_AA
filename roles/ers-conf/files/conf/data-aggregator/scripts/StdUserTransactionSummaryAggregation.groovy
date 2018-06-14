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
 *
 * @author Sumit Chakraborty + Rahul Tiwari
 */
@Log4j
@DynamicMixin
public class StdUserTransactionSummaryAggregation extends AbstractAggregator {
    static final def TABLE = "std_user_transaction_summary_aggregation"
    static final def MONETARY_CATEGORIES = [
            "TOPUP",
            "REVERSE_TOPUP",
            "CREDIT_TRANSFER",
            "REVERSE_CREDIT_TRANSFER",
            "PRODUCT_RECHARGE",
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

    @Value('${StdUserTransactionSummaryAggregation.batch:1000}')
    int limit
    @EqualsAndHashCode
    @ToString
    private static class Key {
        Date date
        String channel
        String senderResellerID
	String resellerMSISDN
	String resellerParentId
        String user
    }
    @Transactional
    @Scheduled(cron = '${StdUserTransactionSummaryAggregation.cron:0 0/30 * * * ?}')
    public void aggregate() {
        def transactions = getTransactions(limit)
        if (transactions) {
            def matchedTransactions = findAllowedProfiles(transactions, MONETARY_CATEGORIES).
                    findAll(successfulTransactions)
            def aggregation = matchedTransactions.collect(transactionInfo).findAll().groupBy
            {
		        new Key( date: it.date, channel: it.channel,
                       senderResellerID: it.senderResellerID, resellerMSISDN: it.senderMSISDN, resellerParentId: it.resellerParentId, 
                        user: it.user
                )
            }
	
            .collect()
                    {
                        key, list ->
                            [key: key, transactionCount: list.size(), transactionAmount: list.sum {it.transactionAmount ?: 0}]
                    }
            updateAggregation(aggregation)
            updateCursor(transactions)
            schedule()
        }
}
    
    private def transactionInfo =
            {
                log.debug("Processing transaction StdUserTransactionSummaryAggregation: ${it}")
                def tr = parser.parse(it.getJSON()).getAsJsonObject()
                log.debug("***************transaction StdTransactionSummaryReportAggregator :" + tr + " **************")
                def channel = asStringOrDefault(parser.parse(it.getJSON()).getAsJsonObject(), "channel")
                def date = it.getEndTime().clone().clearTime()
                def transactionAmount = getBigDecimalFieldFromAnyField(tr, "value", "receivedAmount", "topupAmount")
                def senderPrincipalJsonObject = tr.get("senderPrincipal");
                def receiverPrincipalJsonObject = ""
        def profile =  asStringOrDefault(parser.parse(it.getJSON()).getAsJsonObject(), "profileId")
                profile = MONETARY_CATEGORIES.contains(it.profile) ? it.profile : "OTHER"
                def user = tr.get("senderPrincipal")?.getAsJsonObject()?.get("user")?.getAsJsonObject()?.get("userId")?.getAsString()
		if(!user){
			user = " "
		}
		def senderResellerID = ""
		def resellerParentId = ""
                def senderMSISDN = ""
                def senderamount = new BigDecimal("0")
                if (senderPrincipalJsonObject) {
                    senderResellerID = asString(senderPrincipalJsonObject, "resellerId")
		    resellerParentId = asString(senderPrincipalJsonObject, "parentResellerId")
                    senderMSISDN = asString(senderPrincipalJsonObject, "resellerMSISDN")
                    def transactionProperties = tr.get("transactionProperties")?.getAsJsonObject()
                    tr.get("transactionRows")?.getAsJsonArray()?.iterator().collect
                            {
                                def senderAccountId = it.get("accountSpecifier")?.getAsJsonObject()?.get("accountId")?.getAsString()
                                if (senderResellerID == senderAccountId) {
                                    senderamount = getBigDecimalFieldFromAnyField(it, "value", "amount")
                                }
                            }
                } else if (tr.get("principal")) {
                    senderPrincipalJsonObject = tr.get("principal")?.getAsJsonObject()
                    senderResellerID = asString(senderPrincipalJsonObject, "resellerId")
                    senderMSISDN = asString(senderPrincipalJsonObject, "resellerMSISDN")
	            resellerParentId = asString(senderPrincipalJsonObject, "parentResellerId")
                }
		if(VOUCHER_CATEGORIES.contains(profile)){
                        tr.get("transactionRows")?.getAsJsonArray()?.iterator().collect {
                        log.debug("process transaction row:" + it)
                        transactionAmount = getBigDecimalFieldFromAnyField(it,"value","amount")
                        user = tr.get("principal")?.getAsJsonObject()?.get("user")?.getAsJsonObject()?.get("userId")?.getAsString()

                    }
                }	
                	
                [ date: date, channel: channel,
                  transactionAmount: transactionAmount,
                  senderResellerID: senderResellerID, resellerParentId: resellerParentId, senderMSISDN: senderMSISDN, user: user]
       //}
           
      /* .findAll
            {
                it!= null
            }*/
           /* .groupBy
            {
                new Key( date: it.date, channel: it.channel,
                       senderResellerID: it.senderResellerID,
                        user: it.user
                )
            }
            .collect()
                    {
                        key, list ->
                            [key: key, transactionCount: list.size(), transactionAmount: list.sum {it.transactionAmount ?: 0}]
                    } */
}
    private def updateAggregation(List aggregation) {
        log.info("Aggregated into StdUserTransactionSummaryAggregation ${aggregation.size()} rows.")
        if (aggregation) {
 		    def date = new Date()
            use(TimeCategory)
                    {
                        date = toSqlDate(date)
                    }
            def sql = "INSERT INTO ${TABLE} (date, resellerId,resellerMSISDN,resellerParentId, user,channel,transactionCount,transactionAmount) values (?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE transactionCount=transactionCount+VALUES(transactionCount), transactionAmount=transactionAmount+VALUES(transactionAmount)"
            def batchUpdate = jdbcTemplate.batchUpdate(sql, [
                    setValues:
                            { ps, i ->
                                ps.setDate(1, toSqlDate(aggregation[i].key.date))
                                ps.setString(2, aggregation[i].key.senderResellerID)
				ps.setString(3, aggregation[i].key.resellerMSISDN)
				ps.setString(4, aggregation[i].key.resellerParentId)
                                ps.setString(5, aggregation[i].key.user)
                                ps.setString(6, aggregation[i].key.channel)
                                ps.setInt(7, aggregation[i].transactionCount)
                                ps.setDouble(8, aggregation[i].transactionAmount)
                            },
                    getBatchSize:
                            { aggregation.size() }
            ] as BatchPreparedStatementSetter)
       }
    }
}
