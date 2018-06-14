package se.seamless.ers.components.dataaggregator.aggregator

import groovy.time.TimeCategory
import groovy.transform.EqualsAndHashCode
import groovy.transform.ToString
import groovy.util.logging.Log4j

import java.math.BigDecimal;
import java.util.Date;
import java.util.concurrent.TimeUnit

import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.BatchPreparedStatementSetter
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.transaction.annotation.Transactional

import StdPromotionSystemTransactionStatisticsAggregator.Key;


/**
 * Aggregates transaction rows per date, senderResellerId, channel, reciverResellerLevel, senderResellerLevel, senderRegion, receiverRegion and eachtransactionsamount
 * calculates amount of transaction and transaction count
 * @author Sk Safiruddin
 */
@Log4j
@DynamicMixin
public class StdPromotionSystemTransactionStatisticsAggregator extends AbstractAggregator
{
	static final def TABLE = "promotionsystsem_transaction_statistics_aggregation"
	
	@Value('${StdPromotionSystemTransactionStatisticsAggregator.batch}')
	int limit
	
	@Value('${StdPromotionSystemTransactionStatisticsAggregator.isDynamic}')
	boolean isDynamic
	
	@Value('${StdPromotionSystemTransactionStatisticsAggregator.resellerLevels}')
	String resellerLevels
	
	@Value('${StdPromotionSystemTransactionStatisticsAggregator.allowed_profiles}')
	String allowed_profiles
	
	def allowedProfiles = []
	
	def resellerLevel = [:]
	
	@EqualsAndHashCode
	@ToString
	private static class Key implements Comparable<Key> {
		Date date
		String senderResellerId
		Integer reciverResellerLevel
		Integer senderResellerLevel
		String channel
		String senderRegion
		String receiverRegion
		String productId
		String profileId
		BigDecimal eachtransactionsamount

		int compareTo(Key other) {
			profileId <=> other.profileId ?: productId <=> other.productId ?: date <=> other.date ?: senderResellerId <=> other.senderResellerId ?: reciverResellerLevel <=> other.reciverResellerLevel ?: senderResellerLevel <=> other.senderResellerLevel ?: channel <=> other.channel ?: senderRegion <=> other.senderRegion ?: receiverRegion <=> other.receiverRegion ?: eachtransactionsamount <=> other.eachtransactionsamount
		}
	}
	
	@Transactional
	@Scheduled(cron  = '${StdPromotionSystemTransactionStatisticsAggregator.cron}')
	public void aggregate()
	{
		resellerLevel = [:]
		if(resellerLevels){
			for (resellerLevelValue in resellerLevels.tokenize(',')) {
				def resellerLevelArray = resellerLevelValue.split(":")
				resellerLevel.put(resellerLevelArray[0].asType(String),resellerLevelArray[1].asType(int))
				
				
			}
		}
		
		allowedProfiles = []
		
		if(allowed_profiles){
			def allowedProfile = allowed_profiles.split(",")
			for (resellerProfile in allowedProfile) {
                    allowedProfiles.add(resellerProfile.asType(String))
            }
		}
			
		def txTransactions;
		log.info("StdPromotionSystemTransactionStatisticsAggregator fetching transaction")
		use(TimeCategory) {
			txTransactions = getTransactions(new Date() - 1.minute, limit)
		}
		
		if (!txTransactions) {
			log.info("No transactions returned")	
			return
		}
		def matchedTransactions = txTransactions.findAll {
			it != null && it.resultCode == 0 && allowedProfiles.contains(it.getProfile())
		}
		
		
		if (matchedTransactions) {
				def transactions = matchedTransactions.collect{
				def date = it.getEndTime().clearTime()
				def tr = parser.parse(it.getJSON()).getAsJsonObject()
				def profileId = tr.get("profileId")?.getAsString()
				def productId = tr.get("productId")?.getAsString()
				//Dont count support transfer in promotions
				if(productId && !productId.toString().equals("UNKNOWN"))
				{
					def senderResellerId = null
					def senderPrincipalJsonObject = tr.get("senderPrincipal")?.getAsJsonObject()
					if (senderPrincipalJsonObject) {
						senderResellerId = senderPrincipalJsonObject.get("resellerId")?.getAsString() ?: senderPrincipalJsonObject.get("subscriberId")?.getAsString()
					}
					
					def channel = tr.get("channel")?.getAsString()
					
					def reciverResellerLevel = -1;
					
					def receiverPrincipalJsonObject = tr.get("receiverPrincipal")?.getAsJsonObject()
					if (receiverPrincipalJsonObject) {
						reciverResellerLevel = resellerLevel[receiverPrincipalJsonObject.get("resellerTypeId")?.getAsString()] ?: -1
					}
					else {
						receiverPrincipalJsonObject = tr.get("topupPrincipal")?.getAsJsonObject()
						if (receiverPrincipalJsonObject) {
							reciverResellerLevel = resellerLevel["subscriber"]?:-1;
						}
					}
					
					def senderResellerLevel = -1;
					
					if (senderPrincipalJsonObject) {
						senderResellerLevel = resellerLevel[senderPrincipalJsonObject.get("resellerTypeId")?.getAsString()] ?: -1
					}
					else {
						senderPrincipalJsonObject = tr.get("topupPrincipal")?.getAsJsonObject()
						if (senderPrincipalJsonObject) {
							senderResellerLevel = resellerLevel["subscriber"];
						}
					}
					
					def senderRegion
					def receiverRegion
					
					if(isDynamic==true){
						senderRegion =  tr.get("transactionProperties")?.getAsJsonObject()?.get("map")?.getAsJsonObject()?.get("senderRegionId")?.getAsString()
						receiverRegion = tr.get("transactionProperties")?.getAsJsonObject()?.get("map")?.getAsJsonObject()?.get("receiverRegionId")?.getAsString()
					}else{
						senderRegion =  tr.get("senderPrincipal")?.getAsJsonObject()?.getAsJsonObject()?.get("groupIds")?.get( 0 )?.getAsString()
						receiverRegion = tr.get("receiverPrincipal")?.getAsJsonObject()?.getAsJsonObject()?.get("groupIds")?.get( 0 )?.getAsString()
					}
					
					def eachtransactionsamount = getBigDecimalFieldFromAnyField(tr, "value", "requestedTransferAmount", "requestedTopupAmount")
					
					def transactionamount = getBigDecimalFieldFromAnyField(tr, "value", "requestedTransferAmount", "requestedTopupAmount")
					def transactioncount = 1
					if (("REVERSE_CREDIT_TRANSFER" == profileId || "REVERSE_TOPUP" == profileId) && transactionamount)
					{
						transactionamount = transactionamount.negate()
						transactioncount = -1;
					}
					
					[date: date, senderResellerId: senderResellerId, channel: channel, reciverResellerLevel: reciverResellerLevel, senderResellerLevel: senderResellerLevel,
					senderRegion: senderRegion, receiverRegion: receiverRegion, eachtransactionsamount: eachtransactionsamount, transactionamount: transactionamount, 
					transactioncount: transactioncount, productId: productId, profileId: profileId]
					
					}
				}
				.findAll {
				it != null
				}
				.groupBy {
				new Key(date: it.date, senderResellerId: it.senderResellerId, reciverResellerLevel: it.reciverResellerLevel, senderResellerLevel: it.senderResellerLevel, channel: it.channel, senderRegion: it.senderRegion, receiverRegion: it.receiverRegion, eachtransactionsamount: it.eachtransactionsamount, productId: it.productId, profileId: it.profileId)
				}
				.collect { key, list ->
				[key : key, totalAmount:list.sum { it.transactionamount ?: 0 }, totalTransactioncount: list.sum { it.transactioncount ?:0 }]
			}
				
			updateAggregation(transactions)
		}
		updateCursor(txTransactions)
		schedule(50, TimeUnit.MILLISECONDS)
	}

	private def updateAggregation(List aggregation)
	{
		log.info("Aggregated into ${aggregation.size()} rows.")
		if(aggregation)
		{
			def sql = "insert into ${TABLE} (aggregationDate, senderResellerId, receiverResellerLevel, senderResellerLevel, productId, channel, senderRegion, receiverRegion, eachTransactionAmount, totalAmount, totalTransactionCount) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE totalAmount=totalAmount+VALUES(totalAmount), totalTransactionCount=totalTransactionCount+VALUES(totalTransactionCount)"
			def batchUpdate = jdbcTemplate.batchUpdate(sql, [
				setValues:
				{	ps, i ->
					def row = aggregation[i]
					ps.setTimestamp(1, toSqlTimestamp(row.key.date))
					ps.setString(2, row.key.senderResellerId)
					ps.setInt(3, row.key.reciverResellerLevel)
					ps.setInt(4, row.key.senderResellerLevel)
					ps.setString(5, (row.key.productId == null ? row.key.profileId : row.key.productId))
					ps.setString(6, row.key.channel)
					ps.setString(7, (row.key.senderRegion == null | row.key.senderRegion=="" ? "NO_REGION":row.key.senderRegion))
					ps.setString(8, (row.key.receiverRegion == null | row.key.receiverRegion=="" ? "NO_REGION":row.key.receiverRegion))
					ps.setBigDecimal(9, row.key.eachtransactionsamount)
					ps.setBigDecimal(10, row.totalAmount)
					ps.setInt(11, row.totalTransactioncount)
				},
				getBatchSize:
				{ aggregation.size() }
			] as BatchPreparedStatementSetter)
		}
	}
}
