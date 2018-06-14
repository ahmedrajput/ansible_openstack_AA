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

import com.seamless.ers.interfaces.platform.clients.transaction.model.ERSTransactionResultCode


/**
 * Groups transactions by:
 * <ul>
 * <li>date</li>
 * <li>resultCode</li>
 * <li>profile</li>
 * <li>channel</li>
 * <li>category (monetary or nonmonetary transaction)</li>
 * </ul>
 * Aggregates total number of failed transactions, gropued by above values.
 * <br>
 * Used by reports:
 * <ul>
 * <li>STD_BIR_2008.rptdesign - Transaction Failure Causes Report</li>
 * </ul>
 *
 * @author Michał Podsiedzik
 */
@Log4j
@DynamicMixin
public class StdTransactionsFailuresAggregator extends AbstractAggregator
{
       	static final def TABLE = "std_transaction_failures_aggregation"
       
		@Value('${StdTransactionsFailuresAggregator.allowed_profiles:TOPUP,REVERSE_TOPUP,CREDIT_TRANSFER,REVERSE_CREDIT_TRANSFER,DATA_BUNDLE,VOT_PURCHASE,VOS_PURCHASE,VOUCHER_REDEEM,SUPPORT_TRANSFER,REVERSE_VOT_PURCHASE,REVERSE_VOS_PURCHASE,PRODUCT_RECHARGE,PURCHASE}')
		String monetaryCategories
		   
		def MONETARY_CATEGORIES = []
		
       	@Value('${StdTransactionsFailuresAggregator.batch:10000}')
       	int limit

       	@EqualsAndHashCode
       	@ToString
       	private static class Key
       	{
       		Date date
       		String category
       		String channel
       		String profile
       		int resultCode
       		String resellerParent
       		String resellerPath
       	}

       	@Transactional
       	@Scheduled(cron = '${StdTransactionsFailuresAggregator.cron:0 0/30 * * * ?}')
       	public void aggregate()
       	{
			MONETARY_CATEGORIES = []
			   
			if(monetaryCategories){
		    def allowedProfileList = monetaryCategories.split(",")
			  for (resellerProfile in allowedProfileList) {
				   MONETARY_CATEGORIES.add(resellerProfile.asType(String))
			  }
			}
			
       		def transactions = getTransactions(limit)
       		if (transactions)
       		{
				   
       			def aggregation = transactions.findAll(failedTransactions)
       					.collect(failureInfo)
       					.groupBy(key)
       					.collect(statistics)

       			updateAggregation(aggregation)
       			updateCursor(transactions)
       			schedule()
       		}
       	}

       	private def failureInfo =
       	{
       		log.debug("Processing transaction: ${it}")
        def tr = parser.parse(it.getJSON());
       		def channel = asStringOrDefault(tr?.getAsJsonObject(), "channel")
       		def productId = asStringOrDefault(tr?.getAsJsonObject(), "productId")
       		def profile =  asStringOrDefault(tr?.getAsJsonObject(), "profileId")
       		def isRetrieveStock=it.getErsTransaction()?.getTransactionProperties()?.get("retrieveStock");
       		def senderData = findSenderPrincipalAlongWithTransactionRow(tr)
       		def resellerParent
        def resellerPath

        def senderPrincipalJsonObject = tr.get("senderPrincipal")?.getAsJsonObject()
       		def principalJsonObject = tr.get("principal")?.getAsJsonObject()

       		if (senderPrincipalJsonObject)
       		{
       			resellerParent = senderPrincipalJsonObject.get("parentResellerId")?.getAsString() ?: tr.get("principal")?.getAsJsonObject()?.get("resellerData")?.getAsJsonObject()?.get("parentResellerId")?.getAsString()
       			resellerPath =   senderPrincipalJsonObject.get("resellerPath")?.getAsString() ?: tr.get("principal")?.getAsJsonObject()?.get("resellerData")?.getAsJsonObject()?.get("resellerPath")?.getAsString()
       		}
       		else if(principalJsonObject)
       		{
       			resellerParent = principalJsonObject.get("parentResellerId")?.getAsString()
       			resellerPath =  principalJsonObject.get("resellerPath")?.getAsString()
       		}
       		else if(senderData != null && senderData.principal !=null)
       		{
       			resellerParent = asString(senderData.principal, "parentResellerId")
       			resellerPath = asString(senderData.principal, "resellerPath")
       		}


        profile = translatedTransactionProfile(channel,it.profile);
       		if (isRetrieveStock == "true")
       		{
       			profile = "RETRIEVE_STOCK"
       		}
       		if("CASHIN"==productId || "MM2ERS"==productId){
       			profile=productId
       		}

       		def category = 	MONETARY_CATEGORIES.contains(it.profile) ? "MONETARY" : "NONMONETARY"
       		[date: it.getEndTime().clone().clearTime(), profile: profile, resultCode: it.resultCode, channel: channel, category: category,resellerParent:resellerParent, resellerPath:resellerPath]
       	}

       	private def key =
       	{
       		log.debug("Creating key from ${it}")
       		new Key(date: it.date, category: it.category, channel: it.channel, profile: it.profile, resultCode: it.resultCode,resellerParent:it.resellerParent,resellerPath:it.resellerPath)
       	}

       	private def statistics =
       	{      	key, list ->
       		[key: key, total: list.size()]
       	}

       	private def updateAggregation(List aggregation)
       	{
       		log.info("Aggregated into ${aggregation.size()} rows.")
       		if(aggregation)
       		{
       				def sql = "INSERT INTO ${TABLE} (aggregationDate, category, channel, profile, resultCode, resultCodeDescription, total, reseller_parent,reseller_path) VALUES (?, ?, ?, ?, ?, ?, ?,?,?)"
				   
				   def batchUpdate = jdbcTemplate.batchUpdate(sql, [
       				setValues:
       				{      	ps, i ->
       					def row = aggregation[i]
       					ps.setDate(1, toSqlDate(row.key.date))
       					ps.setString(2, row.key.category)
       					ps.setString(3, row.key.channel)
       					ps.setString(4, row.key.profile)
       					ps.setInt(5, row.key.resultCode)
       					ps.setString(6, ERSTransactionResultCode.lookupResultCode(row.key.resultCode))
       					ps.setInt(7, row.total)
       					ps.setString(8, row.key.resellerParent)
       					ps.setString(9, row.key.resellerPath)
       				},
       				getBatchSize:
       				{ aggregation.size() }
       			] as BatchPreparedStatementSetter)
       		}
       	}
}