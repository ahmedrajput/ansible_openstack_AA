package se.seamless.ers.components.dataaggregator.aggregator

import groovy.time.TimeCategory
import groovy.transform.EqualsAndHashCode
import groovy.transform.ToString
import groovy.util.logging.Log4j
import java.util.concurrent.TimeUnit
import java.text.SimpleDateFormat;
import java.util.Date;
import java.sql.*

import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.BatchPreparedStatementSetter
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.transaction.annotation.Transactional
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.jdbc.core.JdbcTemplate


/**
 * Aggregates details of transactions.
 * Used by reports:
 * <ul>
 * <li></li>
 * </ul>
 *
 * @author Danish Amjad
 */
@Log4j
@DynamicMixin
public class StdTransactionDetailsAggregator extends AbstractAggregator
{
    static final def TABLE = "std_transaction_details_aggregation"
	
	static final def TOTAL_RECEIVING_BALANCE_TABLE = "std_reseller_total_balance_received_aggregation"
   
    @Value('${StdTransactionDetailsAggregator.batch:1000}')
    int limit
	
	@Value('${StdTransactionDetailsAggregator.allowed_profiles:DATA_BUNDLE,TOPUP,CREDIT_TRANSFER,REVERSE_CREDIT_TRANSFER,REVERSE_TOPUP,BLOCK_VOUCHER,UNBLOCK_VOUCHER,VOS_PURCHASE,VOT_PURCHASE,VOUCHER_REDEEM,REVERSE_VOS_PURCHASE,REVERSE_VOT_PURCHASE,REVERSE_VOT_PURCHASE,PRODUCT_RECHARGE,PURCHASE,REVERSAL,MM2ERS,DATA_BUNDLE}')
	String allowed_profiles
	
	def allowedProfiles = []
	
	def reseller_receiving_balance = []

    @EqualsAndHashCode
    @ToString
    private static class Key
    {
        String ersReference
        Date date
		Date endTime
        String resellerId
		String resellerName
		String resellerTypeId
        String resellerMSISDN
        String resellerParent
        String resellerPath
        String receiverMSISDN
        String transactionType
		String transactionResult
        BigDecimal resellerOpeningBalance
        BigDecimal transactionAmount
        BigDecimal resellerClosingBalance
		BigDecimal receiverClosingBalance
		BigDecimal receiverOpeningBalance

    }
    @Transactional
    @Scheduled(cron = '${StdTransactionDetailsAggregator.cron:0 0/30 * * * ?}')
    public void aggregate()
    {
		if(allowed_profiles){
			def allowedProfile = allowed_profiles.split(",")
			for (resellerProfile in allowedProfile) {
					allowedProfiles.add(resellerProfile.asType(String))
			}
		}
		
        def transactions = getTransactions(limit)
        if (transactions)
        {
            def aggregation = findAllowedProfiles(transactions, allowedProfiles)
                    .findAll(successfulTransactions)
                    .collect(transactionInfo)
                    .findAll({it.resellerId != null})
                    .groupBy(key)
                    .collect(statistics)
            updateAggregation(aggregation)
			updateTotalRceivingBalanceAggregation(reseller_receiving_balance)
			reseller_receiving_balance = null
			reseller_receiving_balance = []
            updateCursor(transactions)
            schedule()
        }
    }

    private def transactionInfo =
            {
                log.debug("Processing transaction: ${it}")
                def tr = parser.parse(it.getJSON()).getAsJsonObject()
                log.debug("TransactionData --- "+tr)
                def profileId = it.profile
                def channel = asString(tr, "channel")

                def ersReference = asString(tr, "ersReference")
                def date = it.getStartTime().clone().clearTime()
				def endTime = it.getEndTime()
				
				def transactionResult = tr.get("resultCode")?.getAsString()

                def senderData = findSenderPrincipalAlongWithTransactionRow(tr)
                def resellerId = asString(senderData.principal, "resellerId")
				def resellerName = asString(senderData.principal, "resellerName")
                def resellerMSISDN = asString(senderData.principal, "resellerMSISDN")
                def resellerParent = asString(senderData.principal, "parentResellerId")
                def resellerPath = asString(senderData.principal, "resellerPath")
				def resellerTypeId = asStringOrDefault(senderData.principal, "resellerTypeId")
                def receiverMSISDN = getReceiverMSISDN(tr)
                def transactionType = translatedTransactionProfile(channel, profileId)
                def resellerOpeningBalance = getBigDecimalFieldFromAnyField(senderData.transactionRow, "value", "balanceBefore")
                def transactionAmount = getBigDecimalFieldFromAnyField(senderData.transactionRow, "value", "amount")?.abs()
                def resellerClosingBalance = getBigDecimalFieldFromAnyField(senderData.transactionRow, "value", "balanceAfter")
				
				def receiverOpeningBalance = getReceiverBalanceBefore(tr)==null ? new BigDecimal(0) : getReceiverBalanceBefore(tr).toBigDecimal()
				def receiverClosingBalance = getReceiverBalance(tr)== null ? new BigDecimal(0) : getReceiverBalance(tr)?.toBigDecimal()

				String region = getValueFromPathAsString(tr, "transactionProperties.map.senderRegionName");
				region = (null == region || "".equals(region)) ? "NO_REGION" : region;

                [
                        ersReference          : ersReference,
                        date                  : date,
						endTime               : endTime,
                        resellerId            : resellerId,
						resellerName          : resellerName,
						resellerTypeId        : resellerTypeId,
                        resellerMSISDN        : resellerMSISDN,
                        resellerParent        : resellerParent,
                        resellerPath          : resellerPath,
                        receiverMSISDN        : receiverMSISDN,
                        transactionType       : transactionType,
                        resellerOpeningBalance: resellerOpeningBalance,
                        transactionAmount     : transactionAmount,
                        resellerClosingBalance: resellerClosingBalance,
						receiverOpeningBalance: receiverOpeningBalance,
						receiverClosingBalance: receiverClosingBalance,
						transactionResult     : transactionResult,
						region				  : region
                ]
            }

    private def key =
            {
                log.debug("Creating key from ${it}")
                [
                        ersReference          : it.ersReference,
                        date                  : it.date,
						endTime               : it.endTime,
                        resellerId            : it.resellerId,
						resellerName          : it.resellerName,
						resellerTypeId        : it.resellerTypeId,
                        resellerMSISDN        : it.resellerMSISDN,
                        resellerParent        : it.resellerParent,
                        resellerPath          : it.resellerPath,
                        receiverMSISDN        : it.receiverMSISDN,
                        transactionType       : it.transactionType,
                        resellerOpeningBalance: it.resellerOpeningBalance,
                        transactionAmount     : it.transactionAmount,
                        resellerClosingBalance: it.resellerClosingBalance,
						receiverOpeningBalance: it.receiverOpeningBalance,
						receiverClosingBalance: it.receiverClosingBalance,
						transactionResult     : it.transactionResult,
						region				  : it.region
					    
                ]
            }

    private def statistics =
            {	key, list ->
                [
                        key             : key
                ]
            }

    private def updateAggregation(List aggregation)
    {
        log.info("Aggregated into ${aggregation.size()} rows.")
        if(aggregation)
        {
            def sql = "INSERT INTO ${TABLE} (ers_reference, date, reseller_id, reseller_msisdn, reseller_parent, reseller_path, receiver_msisdn, transaction_type, reseller_opening_balance, transaction_amount, reseller_closing_balance,reseller_name,receiver_opening_balance,receiver_closing_balance,end_time,reseller_type_id,transaction_result, region) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ? ,?, ?, ?, ?, ?, ?, ?)"

            def batchUpdate = jdbcTemplate.batchUpdate(sql, [
                    setValues:
                            { ps, i ->
                                def row = aggregation[i]
                                int index=0
                                ps.setString(++index, row.key.ersReference)
                                ps.setDate(++index,toSqlDate(row.key.date))
								if(row.key.resellerId)
									ps.setString(++index,row.key.resellerId)
								if(row.key.resellerMSISDN)
                                	ps.setString(++index,row.key.resellerMSISDN)
								else
									ps.setString(++index,"")
									
                                ps.setString(++index,row.key.resellerParent)
                                ps.setString(++index,row.key.resellerPath)
								if(row.key.receiverMSISDN)
                                	ps.setString(++index,row.key.receiverMSISDN)
								else
									ps.setString(++index,"")
                                ps.setString(++index,row.key.transactionType)
                                ps.setBigDecimal(++index,row.key.resellerOpeningBalance)
                                ps.setBigDecimal(++index,row.key.transactionAmount)
                                ps.setBigDecimal(++index, row.key.resellerClosingBalance)
								ps.setString(++index,row.key.resellerName)
								ps.setBigDecimal(++index,row.key.receiverOpeningBalance)
								ps.setBigDecimal(++index,row.key.receiverClosingBalance)
								ps.setTimestamp(++index, toSqlTimestamp(row.key.endTime))
								ps.setString(++index,row.key.resellerTypeId)
								ps.setString(++index,row.key.transactionResult)
								ps.setString(++index,row.key.region)
								
								if(row.key.transactionType.equals("CREDIT_TRANSFER") || row.key.transactionType.equals("SUPPORT_TRANSFER") || row.key.transactionType.equals("MM2ERS"))
								{
									reseller_receiving_balance.add(row.key.receiverMSISDN+","+row.key.transactionAmount)
								}
                                
                            },
                    getBatchSize:
                            { aggregation.size() }
            ] as BatchPreparedStatementSetter)
        }
    }
	
	private def updateTotalRceivingBalanceAggregation(List reseller_receiving_balance)
	{
		log.info("Aggregated into std_reseller_total_balance_received_aggregation,  ${reseller_receiving_balance.size()} rows.")
		if(reseller_receiving_balance)
		{
			def sql = "INSERT INTO ${TOTAL_RECEIVING_BALANCE_TABLE} (reseller_msisdn,total_received_balance) VALUES (?, ?) ON DUPLICATE KEY UPDATE total_received_balance = total_received_balance + VALUES(total_received_balance)"
			def batchUpdate = jdbcTemplate.batchUpdate(sql, [
					setValues:
							{ ps, i ->
								def row = reseller_receiving_balance[i]
								
								def resellerMSISDN
								def receivedBalance
				
								String[] reseller_receiving_balance_array
								reseller_receiving_balance_array = row.split(',');
		
								if(reseller_receiving_balance_array.size() == 2)
								{
									resellerMSISDN = reseller_receiving_balance_array[0]
								    receivedBalance = reseller_receiving_balance_array[1]		
								}
								
								
								int index=0
								ps.setString(++index, resellerMSISDN)
								ps.setBigDecimal(++index,new BigDecimal(receivedBalance.replaceAll(",", "")))
								
							},
					getBatchSize:
							{ reseller_receiving_balance.size() }
			] as BatchPreparedStatementSetter)
		}
	}
}
