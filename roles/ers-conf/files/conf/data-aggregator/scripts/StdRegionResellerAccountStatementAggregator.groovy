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

import StdRegionResellerAccountStatementAggregator.Key;


/**
 * Aggregates transaction rows per reseller and calculates amount of credit, debit transfers with coressponding
 * balace before and after values. 
 * 
 * @author Bilal.Mirza
 */
@Log4j
@DynamicMixin
public class StdRegionResellerAccountStatementAggregator extends AbstractAggregator
{
	static final def INOUT_TABLE = "std_region_reseller_account_statement_inout_aggregation"
	
	@Value('${StdRegionResellerAccountStatementAggregator.batch}')
	int limit

	def allowedProfiles = [
		"TOPUP",
		"CREDIT_TRANSFER",
		"REVERSE_CREDIT_TRANSFER",
		"REVERSAL",
		"SUPPORT_TRANSFER",
        "VOS_PURCHASE",
        "VOT_PURCHASE",
        "REVERSE_VOS_PURCHASE",
        "REVERSE_VOT_PURCHASE",
		"PRODUCT_RECHARGE",
		"CASHIN",
        "DATA_BUNDLE",
        "MM2ERS"
	]

    //the receiver is not a reseller
    def INVALID_RECEIVER_PROFILES = [
            "TOPUP",
            "VOS_PURCHASE",
            "VOT_PURCHASE",
            "REVERSE_VOS_PURCHASE",
            "REVERSE_VOT_PURCHASE"

    ]
	
	@EqualsAndHashCode
	@ToString
	private static class Key implements Comparable<Key> {
		Date date
		String resellerId
		String resellerRegion

		int compareTo(Key other) {
			date <=> other.date ?: resellerId <=> other.resellerId ?: resellerRegion <=> other.resellerRegion
		}
	}
	
	@Transactional
	@Scheduled(cron  = '${StdRegionResellerAccountStatementAggregator.cron}')
	public void aggregate()
	{
		def txTransactions;
		log.info("StdRegionResellerAccountStatementAggregator fetching transaction")
		use(TimeCategory) {
			txTransactions = getTransactions(new Date() - 1.minute, limit)
		}
		
		if (!txTransactions) {
			log.info("No transactions returned")
			return
		}
		def matchedTransactions = findAllowedProfiles(txTransactions,allowedProfiles).
                findAll(successfulTransactions)

		if (matchedTransactions) 
		{
			def sendertransactions = matchedTransactions.collect
			{
				def date = it.getEndTime().clearTime()
				def tr = parser.parse(it.getJSON()).getAsJsonObject()
				def reference = tr.get("ersReference")?.getAsString()
				def profileId = tr.get("profileId")?.getAsString()
				
				
				def senderPrincipalJsonObject = tr.get("senderPrincipal")?.getAsJsonObject()?:tr.get("principal")?.getAsJsonObject()
				if (senderPrincipalJsonObject) {
					def senderResellerId =  asString(senderPrincipalJsonObject, "resellerId")
					def senderResellerTypeId =  asString(senderPrincipalJsonObject, "resellerTypeName")
					def senderResellerName =  asString(senderPrincipalJsonObject, "resellerName")
					
					def transactionProperties=tr.get("transactionProperties")?.getAsJsonObject()
					def senderResellerRegion = tr.get("transactionProperties")?.getAsJsonObject()?.get("map")?.getAsJsonObject()?.get("senderRegionName")?.getAsString()
					if (senderResellerRegion == null)
					{
							senderResellerRegion = "NO_REGION"
					}
					
					def senderamount = new BigDecimal("0")
					def senderBalanceBefore = new BigDecimal("0")
					def senderBalanceAfter = new BigDecimal("0")
					def accountId = ""
					senderPrincipalJsonObject.get("accounts")?.getAsJsonArray().iterator().collect
					{
					  accountId=it.get("accountSpecifier")?.getAsJsonObject()?.get("accountId")?.getAsString()
					}
					tr.get("transactionRows")?.getAsJsonArray()?.iterator().collect
					{

							def senderAccountId=it.get("accountSpecifier")?.getAsJsonObject()?.get("accountId")?.getAsString()
							if(accountId == senderAccountId)
							{
									senderamount = getBigDecimalFieldFromAnyField(it, "value", "amount")
									senderBalanceBefore = getBigDecimalFieldFromAnyField(it, "value", "balanceBefore")
									senderBalanceAfter = getBigDecimalFieldFromAnyField(it, "value", "balanceAfter")
							}

					}
					
					if(senderamount != 0)
					{
						def senderCredit = 0
						def senderDebit = 0
						
						log.info("senderamount : "+senderamount)
						
						if (senderamount > 0)
						{
							senderCredit = senderamount
						}
						else
						{
							senderDebit = -senderamount
						}
						[date: date, resellerId: senderResellerId, resellerTypeId: senderResellerTypeId, resellerName: senderResellerName,
							resellerRegion: senderResellerRegion, credit: senderCredit, debit: senderDebit, balanceBefore: senderBalanceBefore, balanceAfter: senderBalanceAfter]
					}
				}
			}
			.findAll {
				it != null && it.resellerId != null
			}
			.groupBy {
				new Key(date: it.date, resellerId: it.resellerId, resellerRegion: it.resellerRegion)
			}
			.collect { key, list ->
				[key : key, resellerTypeId: list.first().resellerTypeId, resellerName: list.first().resellerName,
					credit:list.sum { it.credit ?: 0 }, debit:list.sum { it.debit ?: 0 }, balanceBefore: list.first().balanceBefore, balanceAfter: list.last().balanceAfter ]
			}
			updateAggregation(sendertransactions)
			
			
			def receivertransactions = matchedTransactions.collect{
				def date = it.getEndTime().clearTime()
				def tr = parser.parse(it.getJSON()).getAsJsonObject()
				def reference = tr.get("ersReference")?.getAsString()
				def profileId = tr.get("profileId")?.getAsString()
				
				def receiverPrincipalJsonObject = tr.get("receiverPrincipal")?.getAsJsonObject()
				if (receiverPrincipalJsonObject && !INVALID_RECEIVER_PROFILES.contains(profileId)) {
					def receiverResellerId =  asString(receiverPrincipalJsonObject, "resellerId")
					def receiverResellerTypeId =  asString(receiverPrincipalJsonObject, "resellerTypeName")
					def receiverResellerName =  asString(receiverPrincipalJsonObject, "resellerName")
					
					def transactionProperties=tr.get("transactionProperties")?.getAsJsonObject()
					def receiverResellerRegion= tr.get("transactionProperties")?.getAsJsonObject()?.get("map")?.getAsJsonObject()?.get("receiverRegionName")?.getAsString()
					
					def receiveramount = new BigDecimal("0")
					def receiverBalanceBefore = new BigDecimal("0")
					def receiverBalanceAfter = new BigDecimal("0")
					
					def accountId = ""
					receiverPrincipalJsonObject.get("accounts")?.getAsJsonArray().iterator().collect
					{
					  accountId=it.get("accountSpecifier")?.getAsJsonObject()?.get("accountId")?.getAsString()
					}
					
					tr.get("transactionRows")?.getAsJsonArray()?.iterator().collect
					{

							def receiveraccountId=it.get("accountSpecifier")?.getAsJsonObject()?.get("accountId")?.getAsString()
							if(accountId == receiveraccountId)
							{
									receiveramount = getBigDecimalFieldFromAnyField(it, "value", "amount")
									receiverBalanceBefore = getBigDecimalFieldFromAnyField(it, "value", "balanceBefore")
									receiverBalanceAfter = getBigDecimalFieldFromAnyField(it, "value", "balanceAfter")
							}
					}
					
					if(receiveramount != 0)
					{
						def receiverCredit = 0
						def receiverDebit = 0
						
						log.info("receiveramount : "+receiveramount)
						
						if (receiveramount > 0)
						{
							receiverCredit = receiveramount
						}
						else
						{
							receiverDebit = -receiveramount
						}
						
						[date: date, resellerId: receiverResellerId, resellerTypeId: receiverResellerTypeId, resellerName: receiverResellerName,
							resellerRegion: receiverResellerRegion, credit: receiverCredit, debit: receiverDebit, balanceBefore: receiverBalanceBefore, balanceAfter: receiverBalanceAfter]
					}
				}
			}
			.findAll {
				it != null && it.resellerId != null
			}
			.groupBy {
				new Key(date: it.date, resellerId: it.resellerId, resellerRegion: it.resellerRegion)
			}
			.collect { key, list ->
				[key : key, resellerTypeId: list.first().resellerTypeId, resellerName: list.first().resellerName,
					credit:list.sum { it.credit ?: 0 }, debit:list.sum { it.debit ?: 0 }, balanceBefore: list.first().balanceBefore, balanceAfter: list.last().balanceAfter ]
			}
			updateAggregation(receivertransactions)
			
		}
		
		updateCursor(txTransactions)
		schedule(50, TimeUnit.MILLISECONDS)
	}

	private def updateAggregation(List aggregation)
	{
		log.info("Aggregated into ${aggregation.size()} rows.")
		if(aggregation)
		{
			
			def sql = "insert into ${INOUT_TABLE} (aggregationDate, resellerId, resellerName, resellerTypeId, resellerRegion, credit, debit) values (?, ?, ?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE credit=credit+VALUES(credit), debit=debit+VALUES(debit)"
			def batchUpdate = jdbcTemplate.batchUpdate(sql, [
				setValues:
				{	ps, i ->
					def row = aggregation[i]
					ps.setTimestamp(1, toSqlTimestamp(row.key.date))
					ps.setString(2, row.key.resellerId)
					ps.setString(3, row.resellerName)
					ps.setString(4, row.resellerTypeId)
					ps.setString(5, (row.key.resellerRegion == null ? "NO_REGION":row.key.resellerRegion))
					ps.setBigDecimal(6, row.credit)
					ps.setBigDecimal(7, row.debit)
				},
				getBatchSize:
				{ aggregation.size() }
			] as BatchPreparedStatementSetter)
		}
	}
}
