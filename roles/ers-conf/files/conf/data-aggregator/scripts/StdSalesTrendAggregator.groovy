package se.seamless.ers.components.dataaggregator.aggregator

import groovy.time.TimeCategory
import groovy.transform.EqualsAndHashCode
import groovy.transform.ToString
import groovy.util.logging.Log4j

import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit

import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.BatchPreparedStatementSetter
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.transaction.annotation.Transactional

/**
 *
 * @author Sabir Ali
 */

@Log4j
@DynamicMixin
public class StdSalesTrendAggregator extends AbstractAggregator
{
	static final def TABLE = "std_sales_trend_aggregation";
@Value('${StdSalesTrendAggregator.allowed_profiles:TOPUP,CREDIT_TRANSFER,REVERSE_CREDIT_TRANSFER,REVERSAL,PURCHASE,CASHIN,VOS_PURCHASE,VOT_PURCHASE,REVERSE_VOS_PURCHASE,REVERSE_VOT_PURCHASE,PRODUCT_RECHARGE,SUPPORT_TRANSFER,REVERSE_TOPUP}')
String allowed_profiles
	
	def allowedProfiles = []
	
	@Value('${StdSalesTrendAggregator.vodProfiles:VOS_PURCHASE,VOT_PURCHASE,REVERSE_VOS_PURCHASE,REVERSE_VOT_PURCHASE}')
	String vodProfiles
	
	def VOD_PROFILES = []
	
	@Value('${StdSalesTrendAggregator.batch:1000}')
	int limit
	
	@Value('${StdSalesTrendAggregator.days:30}')
	int days
	
	@EqualsAndHashCode
	@ToString
	private static class Key implements Comparable<Key>
	{
		Date date
		String resellerId
		String resellerName
		String resellerMSISDN
		String resellerType
		String resellerParent
		String resellerPath
		
		int compareTo(Key other) {
			date <=> other.date ?: 
			resellerId <=> other.resellerId ?: 
			resellerName <=> other.resellerName ?:
			resellerMSISDN <=> other.resellerMSISDN ?: 
			resellerType <=> other.resellerType ?: 
			resellerParent <=> other.resellerParent ?:
			resellerPath <=> other.resellerPath  
		}
	}

	@Transactional
	@Scheduled(cron = '${StdSalesTrendAggregator.cron:0 0/2 * * * ?}')
	public void aggregate()
	{
		def txTransactions
		log.info("\n\nStandard Sales Trend Aggregator started feeding transactions\n\n")
		
		use(TimeCategory) {
			txTransactions = getTransactions(new Date() - 1.minute, limit)
			log.debug("StdSalesTrendAggregator fetching transaction")
		}
		
		if (!txTransactions) {
			log.info("No transactions returned")
			return
		}
		
		allowedProfiles = []
		
		if(allowed_profiles){
			def allowedProfileList = allowed_profiles.split(",")
			for (resellerProfile in allowedProfileList) {
					allowedProfiles.add(resellerProfile.asType(String))
			}
		}
		
		
		VOD_PROFILES = []
		
		if(vodProfiles){
			def allowedVodProfileList = vodProfiles.split(",")
			for (resellervodProfile in allowedVodProfileList) {
					VOD_PROFILES.add(resellervodProfile.asType(String))
			}
		}
		
		def matchedTransactions = findAllowedProfiles(txTransactions,allowedProfiles).findAll(successfulTransactions)
		log.debug("Preparing aggregation. List: " + matchedTransactions)
		if (matchedTransactions)
		{
			def sendertransactions = matchedTransactions.collect
			{
				
				def date = it.getEndTime().clone().clearTime()
				def tr = parser.parse(it.getJSON()).getAsJsonObject()
				def profileId = tr.get("profileId")?.getAsString()
				def productId = tr.get("productId")?.getAsString()
				def senderPrincipalJsonObject = tr.get("senderPrincipal")?.getAsJsonObject()?:tr.get("principal")?.getAsJsonObject()
				def retrieveStock=it.getErsTransaction()?.getTransactionProperties()?.get("retrieveStock")
                log.info("Is Retrieve Stock" + retrieveStock)
                if (retrieveStock && retrieveStock.equalsIgnoreCase("true"))
                {
                	senderPrincipalJsonObject = tr.get("receiverPrincipal")?.getAsJsonObject()
                }
				if (senderPrincipalJsonObject && (productId || VOD_PROFILES.contains(profileId)))
				{
					def resellerId =  asString(senderPrincipalJsonObject, "resellerId")
					def resellerName = asString(senderPrincipalJsonObject, "resellerName")
					def resellerMSISDN = asString(senderPrincipalJsonObject, "resellerMSISDN")
					def resellerType = asString(senderPrincipalJsonObject, "resellerTypeName")
					def resellerParent = asString(senderPrincipalJsonObject, "parentResellerId")
					def resellerPath = asString(senderPrincipalJsonObject, "resellerPath")
					def accountId = ""
					senderPrincipalJsonObject.get("accounts")?.getAsJsonArray().iterator().collect
					{
					  accountId=it.get("accountSpecifier")?.getAsJsonObject()?.get("accountId")?.getAsString()
					}
					def senderAmount = 0
					tr.get("transactionRows")?.getAsJsonArray()?.iterator().collect
					{
						def senderAccountId=it.get("accountSpecifier")?.getAsJsonObject()?.get("accountId")?.getAsString()
												
						if(accountId == senderAccountId)
						{
							def amount = new BigDecimal("0")
							amount = getBigDecimalFieldFromAnyField(it, "value", "amount")
							if(amount)
							{
								senderAmount = amount.negate()
							}
						}
					    if(!resellerId)
					    {
					    	resellerId = "TEMP"
					    	resellerType = "TEMP"
					    }
					}
					if(resellerId)
					[	
						date: date, 
						resellerId: resellerId,
						resellerName: resellerName, 
						resellerType: resellerType,
						resellerMSISDN: resellerMSISDN, 
						transactionAmount: senderAmount,
						resellerParent: resellerParent,
						resellerPath: resellerPath
					]
				}
			}
			.findAll
			{
				it!= null
			}
			.groupBy
			{
				new Key(date:it.date, resellerId:it.resellerId, resellerName: it.resellerName, 
					resellerMSISDN: it.resellerMSISDN, resellerType: it.resellerType, 
					resellerParent: it.resellerParent,resellerPath: it.resellerPath)
			}
			.collect()
			{
				key, list ->
				[key: key, transactionAmount: list.sum{it.transactionAmount ?: 0}]
			}
			updateAggregation(sendertransactions)
		}
		updateCursor(txTransactions)
		schedule(50, TimeUnit.MILLISECONDS)
	}
		
	private def updateAggregation(List aggregation)
	{
		log.info("Aggregated into ${aggregation.size()} rows.");
		if(aggregation)
		{  
			def sql = """
			INSERT INTO ${TABLE} 
			(aggregationDate, resellerId, resellerName, resellerMSISDN, resellerTypeId, transactionAmount,reseller_parent,reseller_path)
			VALUES (?, ?, ?, ?, ?, ?,?,?) 
			ON DUPLICATE KEY UPDATE 
			transactionAmount=transactionAmount+VALUES(transactionAmount),
			resellerTypeId=VALUES(resellerTypeId),
			resellerMSISDN=VALUES(resellerMSISDN),
			reseller_parent=VALUES(reseller_parent),
			reseller_path=VALUES(reseller_path)
			"""
			
			def batchUpdate = jdbcTemplate.batchUpdate(sql, [
				setValues:
				{ ps, i ->
					if(aggregation[i].key.resellerId){
						ps.setDate(1, toSqlDate(aggregation[i].key.date))
						
						ps.setString(2, aggregation[i].key.resellerId)
						ps.setString(3, aggregation[i].key.resellerName)
						ps.setString(4, aggregation[i].key.resellerMSISDN)
						ps.setString(5, aggregation[i].key.resellerType)
						ps.setBigDecimal(6, aggregation[i].transactionAmount)
						ps.setString(7, aggregation[i].key.resellerParent)
						ps.setString(8, aggregation[i].key.resellerPath)
					}
				
				},
				getBatchSize:
				{ aggregation.size() }
			] as BatchPreparedStatementSetter)
		}
		jdbcTemplate.update("DELETE FROM std_sales_trend_aggregation WHERE (aggregationDate < date_sub(curdate(),INTERVAL ${days} DAY)) OR (resellerId = 'TEMP') ;")
		
		log.info("Deleted sales older than ${days} days.")
	}
	
}