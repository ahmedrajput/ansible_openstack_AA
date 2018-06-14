package se.seamless.ers.components.dataaggregator.aggregator

import groovy.time.TimeCategory
import groovy.transform.EqualsAndHashCode
import groovy.transform.ToString
import groovy.util.logging.Log4j

import java.util.Date
import java.util.concurrent.TimeUnit

import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.BatchPreparedStatementSetter
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.transaction.annotation.Transactional
import org.springframework.jdbc.core.PreparedStatementSetter
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.ColumnMapRowMapper

/**
 * Aggregates Voucher Purchase transactions
 * @author mdalam
 */
@Log4j
@DynamicMixin
public class StdEVoucherDetailedSaleAggregator extends AbstractAggregator {

    static final def TABLE = "dataaggregator.std_evoucher_detailed_sale_aggregation"

    @Autowired
    @Qualifier("refill")
    private JdbcTemplate refill
    @Value('${StdEVoucherDetailedSaleAggregator.batch:1000}')
    int limit
    static final def ALLOWED_PROFILES = [
    		"VOUCHER_PURCHASE",
            "VOS_PURCHASE",
            "VOT_PURCHASE",
            "PURCHASE"
    ]


    @Transactional
    @Scheduled(cron = '${StdEVoucherDetailedSaleAggregator.cron:0 0/30 * * * ?}')
    public void aggregate() {
        def transactions = getTransactions(limit)
        log.info(transactions)
        if (transactions) {
            def aggregation = findAllowedProfiles(transactions, ALLOWED_PROFILES)
					.findAll(successfulTransactions)
                    .collect(transactionInfo)
                    .findAll()

            updateAggregation(aggregation)
            updateCursor(transactions)
            schedule()
        }
    }

    private def transactionInfo = {
        log.debug("Processing transaction: ${it}")
        def tr = parser.parse(it.getJSON()).getAsJsonObject()

        log.debug("TransactionData="+tr)
        def state = tr.get("state")?.getAsString()
        def productId =  getProductId(tr) ?: "UNKOWN"
        if (state == "Completed")
        {
            def ersReference = tr.get("ersReference")?.getAsString()
            def transactionDate = it.getEndTime()
			def voucherSerial = tr.get("purchasedProducts")?.getAsJsonArray()?.iterator().collect
			{
					it.get("rows")?.getAsJsonArray()?.iterator().collect{
							it.get("reference")?.getAsString()
					}.find{it}
			}.find{it}
			
			def expiryDate = tr.get("purchasedProducts")?.getAsJsonArray()?.iterator().collect
			{
					it.get("rows")?.getAsJsonArray()?.iterator().collect{
							Date.parse("yyyy-MM-dd'T'HH:mm:ss",it.get("expiryDate")?.getAsString())
					}.find{it}
			}.find{it}
			
            def resellerId = tr.get("principal")?.getAsJsonObject()?.get("resellerId")?.getAsString()
			def resellerName = tr.get("principal")?.getAsJsonObject()?.get("resellerName")?.getAsString()
            def resellerMSISDN = tr.get("principal")?.getAsJsonObject()?.get("resellerMSISDN")?.getAsString()
	    	def channel = asString(tr, "channel")
	    	def remoteAddress = tr.get("transactionProperties")?.getAsJsonObject()?.get("map")?.getAsJsonObject()?.get("remoteAddress")?.getAsString()
	    	def userId = tr.get("principal")?.getAsJsonObject()?.get("user")?.getAsJsonObject()?.get("userId")?.getAsString()	
      	    def receiverMSISDN = getReceiverMSISDN(tr)
			if (receiverMSISDN == null){
				receiverMSISDN = asStringFromField(tr,"receiverPrincipal","subscriberMSISDN")	
			}
            def faceValueAmount =""
            tr.get("transactionRows")?.getAsJsonArray()?.iterator().collect {
                log.debug("process transaction row:" + it)
                faceValueAmount = getBigDecimalFieldFromAnyField(it,"value","amount")
            }
            
            log.debug("Filtered Values[ersReference="+ersReference+",Amount="+faceValueAmount+",resellerId="+resellerId+",resellerMSISDN="+resellerMSISDN+",receiverMSISDN="+receiverMSISDN+",expiryDate="+expiryDate+",voucherSerial="+voucherSerial+",resellerName="+resellerName+",transactionDate="+transactionDate+",channel="+channel+",remoteAddress="+remoteAddress+",userId="+userId+",productId="+productId+"]")
            [ersReference:ersReference,
			 transactionDate: transactionDate,
			 faceValueAmount: faceValueAmount,
			 resellerId: resellerId,
			 resellerMSISDN: resellerMSISDN,
			 receiverMSISDN: receiverMSISDN,
			 expiryDate: expiryDate,
			 voucherSerial: voucherSerial,
			 resellerName: resellerName,
			 channel: channel,
			 remoteAddress: remoteAddress,
			 userId: userId,
			 productId: productId
			]
        }
    }
    def getProductId =
            {
                //successful VOT, VOS
                def productID = it.get("transactionProperties")?.getAsJsonObject()?.
                        get("map")?.getAsJsonObject()?.get("productSKU")?.getAsString()

                if(productID) {
                    return productID
                }
                return  null;

            }

    private def updateAggregation(List aggregation) {
        log.info("Aggregated into ${aggregation.size()} rows.")
        if(aggregation) {
            def sql = "INSERT INTO ${TABLE} (ersReference,voucher_serial,reseller_name,reseller_id,reseller_msisdn,receiver_msisdn,expiry_date,amount,sell_date,channel,remoteAddress,userId,productId) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
            def batchUpdate = jdbcTemplate.batchUpdate(sql, [
                    setValues: { ps, i ->
						ps.setString(1, aggregation[i].ersReference)
						ps.setString(2, aggregation[i].voucherSerial)
						ps.setString(3, aggregation[i].resellerName)
						ps.setString(4, aggregation[i].resellerId)
						ps.setString(5, aggregation[i].resellerMSISDN)
						ps.setString(6, aggregation[i].receiverMSISDN)
						ps.setTimestamp(7, toSqlTimestamp(aggregation[i].expiryDate))
						ps.setBigDecimal(8, aggregation[i].faceValueAmount.toDouble())
						ps.setTimestamp(9, toSqlTimestamp(aggregation[i].transactionDate))
						ps.setString(10, aggregation[i].channel)
						ps.setString(11, aggregation[i].remoteAddress)
						ps.setString(12, aggregation[i].userId)
						ps.setString(13, aggregation[i].productId)
                    },
                    getBatchSize: { aggregation.size() }
            ] as BatchPreparedStatementSetter)
        }
    }

}
