package se.seamless.ers.components.dataaggregator.aggregator

import groovy.time.TimeCategory
import groovy.transform.EqualsAndHashCode
import groovy.transform.ToString
import groovy.util.logging.Log4j

import java.util.concurrent.TimeUnit

import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.BatchPreparedStatementSetter
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.transaction.annotation.Transactional


/**
 * Aggregates count of successful with coresponding amounts per reseller.
 * Used by reports:
 * <ul>
 * <li></li>
 * </ul>
 *
 * @author Danish Amjad
 */
@Log4j
@DynamicMixin
public class StdTransactionsSummaryAggregator extends AbstractAggregator
{

    static final def TABLE = "std_transactions_summary_aggregation"
   
	@Value('${StdTransactionsSummaryAggregator.allowed_profiles:TOPUP,CREDIT_TRANSFER,PURCHASE,SUPPORT_TRANSFER}')
	String allowed_profiles
	
	def MONETARY_PROFILES=[]
	
    @Value('${StdTransactionsSummaryAggregator.batch:1000}')
    int limit

    @EqualsAndHashCode
    @ToString
    private static class Key
    {
        Date date
        String resellerId
        String transactionType
        BigDecimal transactionAmount
        String resellerTypeName
        
    }
    @Transactional
    @Scheduled(cron = '${StdTransactionsSummaryAggregator.cron:0 5 0 * * ?}')
    public void aggregate()
    {
		MONETARY_PROFILES = []
		
		if(allowed_profiles){
			def allowedProfileList = allowed_profiles.split(",")
			for (resellerProfile in allowedProfileList) {
					MONETARY_PROFILES.add(resellerProfile.asType(String))
			}
		}
		
        def transactions = getTransactions(limit)
        if (transactions)
        {
            def aggregation = findAllowedProfiles(transactions, MONETARY_PROFILES)
                    .findAll(successfulTransactions)
                    .collect(transactionInfo)
                    .findAll({it.resellerId != null})
                    .groupBy(key)
                    .collect(statistics)
            updateAggregation(aggregation)
            updateCursor(transactions)
            schedule()
        }
    }

    private def transactionInfo =
            {
            	def resellerId
				def resellerTypeName
                log.debug("Processing transaction: ${it}")
                def tr = parser.parse(it.getJSON()).getAsJsonObject()
                log.debug("TransactionData --- "+tr)
                def profileId = it.profile
                def channel = asString(tr, "channel")
                def date = it.getStartTime().clone().clearTime()

                def senderData = findSenderPrincipalAlongWithTransactionRow(tr)
         		
                def transactionType = translatedTransactionProfile(channel, profileId)
                def transactionAmount = getBigDecimalFieldFromAnyField(senderData.transactionRow, "value", "amount")?.abs()
            
				def senderPrincipalJsonObject = tr.get("senderPrincipal")?.getAsJsonObject()
				def principalJsonObject = tr.get("principal")?.getAsJsonObject()
				
				
				
				if (senderPrincipalJsonObject)
				{
					resellerId = senderPrincipalJsonObject.get("resellerId")?.getAsString() ?: tr.get("principal")?.getAsJsonObject()?.get("resellerData")?.getAsJsonObject()?.get("resellerId")?.getAsString()
					resellerTypeName= asString(senderPrincipalJsonObject, "resellerTypeName")?: tr.get("principal")?.getAsJsonObject()?.get("resellerData")?.getAsJsonObject()?.get("resellerTypeName")?.getAsString()
				}
				else if(principalJsonObject)
				{
					resellerId = principalJsonObject.get("resellerId")?.getAsString()
					resellerTypeName=  principalJsonObject.get("resellerTypeName")?.getAsString()
				}
				else if(senderData != null && senderData.principal !=null)
				{
					resellerId = asString(senderData.principal, "resellerId")
				}
				
				[
                        date                  : date,
                        resellerId            : resellerId,
                        transactionType       : transactionType,
                        transactionAmount     : transactionAmount,
                        resellerTypeName      : resellerTypeName
                       
                ]
            }

    private def key =
            {
                log.debug("Creating key from ${it}")
                [
                        date             : it.date,
                        resellerId       : it.resellerId,
                        transactionType  : it.transactionType,
                        transactionAmount: it.transactionAmount,
                        resellerTypeName : it.resellerTypeName
                       
                ]
            }

    private def statistics =
            {	key, list ->
                def totalAmount = 0
                def transactionCount = 0
                list.each
                        {
                            totalAmount += it.transactionAmount
                            transactionCount++
                        }
                [
                        key             : key,
                        transactionCount: transactionCount,
                        totalAmount     : totalAmount
                ]
            }

    private def updateAggregation(List aggregation)
    {
        log.info("Aggregated into ${aggregation.size()} rows.")
        if(aggregation)
        {
            def sql_insert = """INSERT INTO ${TABLE} (date, reseller_id, transaction_type, transaction_count, transaction_amount, resellerType) 
            VALUES (?, ?, ?, ?, ?,?)
            ON DUPLICATE KEY UPDATE
            transaction_count=transaction_count+VALUES(transaction_count),
            transaction_amount=transaction_amount+VALUES(transaction_amount),
            resellerType=VALUES(resellerType)
            """

            log.debug("Using sql query: "+sql_insert);
            log.debug("Using parameters: ${aggregation}")

            def batchUpdate = jdbcTemplate.batchUpdate(sql_insert, [
                    setValues:
                            { ps, i ->
                                def row = aggregation[i]
                                int index=0
                                //Insert
                                ps.setDate(++index,toSqlDate(row.key.date))
                                ps.setString(++index,row.key.resellerId)
                                ps.setString(++index,row.key.transactionType)
                                ps.setInt(++index,row.transactionCount)
                                ps.setBigDecimal(++index,row.totalAmount)
                                ps.setString(++index,row.key.resellerTypeName)
                                
                                
                            }, getBatchSize: { aggregation.size() }
            ] as BatchPreparedStatementSetter)
        }
    }
}
