package se.seamless.ers.components.dataaggregator.aggregator

import groovy.time.TimeCategory
import groovy.util.logging.Log4j
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.BatchPreparedStatementSetter
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.transaction.annotation.Transactional

import java.util.concurrent.TimeUnit

@Log4j
@DynamicMixin
public class StdSearchTransactionAggregator extends AbstractAggregator
{
	static final def TABLE = "std_search_transaction_aggregator";
	static final def DELETE_SQL = "delete from std_search_transaction_aggregator where transactionTime < DATE_SUB(curdate(), INTERVAL 6 MONTH)"
	
	@Autowired
	@Qualifier("refill")
	private JdbcTemplate refill
	
@Value('${StdSearchTransactionAggregator.allowed_profiles:DATA_BUNDLE,TOPUP,CREDIT_TRANSFER,REVERSE_CREDIT_TRANSFER,REVERSE_TOPUP,BLOCK_VOUCHER,UNBLOCK_VOUCHER,VOS_PURCHASE,VOT_PURCHASE,VOUCHER_REDEEM,REVERSE_VOS_PURCHASE,REVERSE_VOT_PURCHASE,REVERSE_VOT_PURCHASE,PRODUCT_RECHARGE,PURCHASE,REVERSAL}')
	String allowed_profiles
	
	def allowedProfiles = []

	@Value('${StdSearchTransactionAggregator.batch:1000}')
	int limit
	
	@Transactional
	@Scheduled(cron = '${StdSearchTransactionAggregator.cron:0/1 * * * * ?}')
	public void aggregate()
	{
		
		log.debug("allowed_profiles are:"+allowed_profiles)
		
		if(allowed_profiles){
			def allowedProfile = allowed_profiles.split(",")
			for (resellerProfile in allowedProfile) {
					allowedProfiles.add(resellerProfile.asType(String))
			}
		}
		
		def txTransactions
		log.debug("\n\nStdSearchTransactionAggregator started feeding transactions\n\n")
		
		use(TimeCategory) {
			txTransactions = getTransactions(new Date() - 1.minute, limit)
			log.debug("Got StdSearchTransactionAggregator;txTransactions; ${txTransactions.size()} ")
		}
		
		if (!txTransactions) {
			log.debug("No transactions returned")
			return
		}
		
		def matchedTransactions = txTransactions.findAll {
			it != null
		}
		
		
		if (matchedTransactions)
		{
			log.info("Got StdSearchTransactionAggregator; matchtransaction; ${matchedTransactions.size()} ")
		
			def collectedTransactions = matchedTransactions.collect
			{
				def serial ="-"
				def comments=""
				def invoiceId = ""
				def transactionStatus = "FAILED"
				def senderResellerId ="-"
				log.debug("transaction json is:"+it.getJSON());
				def date = it.getEndTime()
				log.debug("transaction time:"+date)
				def tr = parser.parse(it.getJSON()).getAsJsonObject()
				
				def channel = asString(tr, "channel")
				def transactionType = allowedProfiles.contains(it.profile) ? it.profile : "Other"
				
				log.debug("transactionType:"+transactionType)
			
				def transactionReference = asString(tr, "ersReference")
				def orignalReference = asString(tr, "originalTransactionErsReference")
				def operation = tr.get("requestType")?.getAsString()
				def resultDescription = tr.get("resultMessage")?.getAsString()
				def transactionResult = tr.get("resultCode")?.getAsString()
				
				def senderData = findSenderPrincipalAlongWithTransactionRow(tr)
				def senderMSISDN = tr.get("principal")?.getAsJsonObject()?.get("resellerMSISDN")?.getAsString()
				def receiverMSISDN = getReceiverMSISDN(tr)
				def receiverResellerId = getReceiverResellerId(tr)
				def resellerParent=""
				if(senderData != null && senderData.principal !=null ){
					resellerParent = asString(senderData.principal, "parentResellerId")
				}
				
				def senderPrincipalJsonObject = tr.get("senderPrincipal")?.getAsJsonObject()
				def principalJsonObject = tr.get("principal")?.getAsJsonObject()
				log.debug("${transactionReference} SENDER_DATA: ${senderData} SENDER_PRINCIPAL: ${senderPrincipalJsonObject} PRINCIPAL: ${principalJsonObject}")
				
				senderResellerId= getSenderResellerId(tr)
				
				log.info("senderResellerId:"+senderResellerId)
				def resellerPath = ""
				if(senderData != null && senderData.principal !=null){
					resellerPath = asString(senderData.principal, "resellerPath")
				}

				def transactionAmount=new BigDecimal(0)
				if(senderData != null && senderData.transactionRow !=null){
					transactionAmount = getBigDecimalFieldFromAnyField(senderData.transactionRow, "value", "amount")?.abs()
				}
				def senderBalanceBefore = getSenderBalanceBefore(tr)== null ? new BigDecimal(0) : getSenderBalanceBefore(tr)?.toBigDecimal()
				def senderBalanceAfter = getSenderBalanceAfter(tr)== null ? new BigDecimal(0) : getSenderBalanceAfter(tr)?.toBigDecimal()
				def receiverBalanceBefore = getReceiverBalanceBefore(tr)==null ? new BigDecimal(0) : getReceiverBalanceBefore(tr).toBigDecimal()
				def receiverBalanceAfter = getReceiverBalance(tr)== null ? new BigDecimal(0) : getReceiverBalance(tr)?.toBigDecimal()
				
				if(transactionType == "TOPUP")
				{
					receiverBalanceBefore = getBigDecimalFieldFromAnyField(tr, "value", "topupBalanceBefore")?.abs()
					receiverBalanceAfter = getBigDecimalFieldFromAnyField(tr, "value", "topupBalanceAfter")?.abs()
				}
				
				comments = tr.get("resultProperties")?.getAsJsonObject()?.get("map")?.getAsJsonObject()?.get("comments")?.getAsString()
				if(comments == null || comments.isEmpty()){
					comments = tr.get("transactionProperties")?.getAsJsonObject()?.get("map")?.getAsJsonObject()?.get("comments")?.getAsString()
				}

				invoiceId = tr.get("resultProperties")?.getAsJsonObject()?.get("map")?.getAsJsonObject()?.get("invoiceId")?.getAsString()
				if(invoiceId == null || invoiceId.isEmpty()){
					invoiceId = tr.get("transactionProperties")?.getAsJsonObject()?.get("map")?.getAsJsonObject()?.get("invoiceId")?.getAsString()
				}

				tr.get("purchasedProducts")?.getAsJsonArray()?.iterator().collect
				{
					it.get("rows").getAsJsonArray()?.iterator().collect
					{
						serial = it.getAt("reference")?.getAsString()
					}
				}
				
				log.debug("transactionResult:"+ transactionResult)
				
				if(transactionResult == "0")
				{
					transactionStatus = "SUCCESS"
				}
				else if(transactionResult == "201" || transactionResult == "1")
				{
					transactionStatus = "PENDING"
				}
				else if(transactionResult == "100")
				{
					transactionStatus = "DENIED"
				}
				else if(transactionResult == "501")
				{
					transactionStatus = "FAILED"
				}
				[
						orignalReference		: orignalReference,
						transactionReference	: transactionReference,
						operation				: operation,
						transactionStatus		: transactionStatus,
						transactionTime			: date,
						transactionType			: transactionType,
						serial					: serial,
						senderMSISDN			: senderMSISDN,
						receiverMSISDN			: receiverMSISDN,
						senderID				: senderResellerId,
						receiverID				: receiverResellerId,
						resellerParent			: resellerParent,
						resellerPath			: resellerPath,
						transactionAmount		: transactionAmount,
						senderBalanceBefore		: senderBalanceBefore,
						senderBalanceAfter		: senderBalanceAfter,
						receiverBalanceBefore	: receiverBalanceBefore,
						receiverBalanceAfter	: receiverBalanceAfter,
						resultDescription  		: resultDescription,
						channel					: channel,
						comments				: comments,
						invoiceId				: invoiceId
				]
			}
			.findAll
			{
				it!= null
			}
			updateAggregation(collectedTransactions)
		}
		log.info("Data inserted in StdSearchTransactionAggregator")
		updateCursor(txTransactions)
		schedule(50, TimeUnit.MILLISECONDS)
	}
	
	private def updateAggregation(List aggregation)
	{
		log.info("Aggregated into ${aggregation.size()} rows.");
		if(aggregation)
		{
			def sql =  """
			INSERT into ${TABLE} (transactionReference, operation, transactionStatus, transactionTime, 
				transactionType,serial,senderMSISDN,receiverMSISDN,senderID,receiverID,resellerParent,resellerPath,transactionAmount,
			senderBalanceBefore,senderBalanceAfter,receiverBalanceBefore,receiverBalanceAfter,resultDescription,channel,comments,invoiceId,orignalReference) 
					 VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE 
			transactionStatus=VALUES(transactionStatus),
			resellerParent=VALUES(resellerParent),
			resellerPath=VALUES(resellerPath),
			transactionAmount=VALUES(transactionAmount),
			senderID=VALUES(senderID),
		 	receiverID=VALUES(receiverID)
			 """
			def batchUpdate = jdbcTemplate.batchUpdate(sql, [
				setValues:
				{ ps, i ->
					ps.setString(1, aggregation[i].transactionReference)
					ps.setString(2, aggregation[i].operation)
					ps.setString(3, aggregation[i].transactionStatus)
					ps.setTimestamp(4, toSqlTimestamp(aggregation[i].transactionTime))
					ps.setString(5, aggregation[i].transactionType)
					ps.setString(6, aggregation[i].serial)
					ps.setString(7, aggregation[i].senderMSISDN)
					ps.setString(8, aggregation[i].receiverMSISDN)
					ps.setString(9, aggregation[i].senderID)
					ps.setString(10, aggregation[i].receiverID)
					ps.setString(11, aggregation[i].resellerParent)
					ps.setString(12, aggregation[i].resellerPath)
					ps.setBigDecimal(13,aggregation[i].transactionAmount)
					ps.setBigDecimal(14,aggregation[i].senderBalanceBefore)
					ps.setBigDecimal(15,aggregation[i].senderBalanceAfter)
					ps.setBigDecimal(16,aggregation[i].receiverBalanceBefore)
					ps.setBigDecimal(17,aggregation[i].receiverBalanceAfter)
					ps.setString(18, aggregation[i].resultDescription)
					ps.setString(19, aggregation[i].channel)
					ps.setString(20, aggregation[i].comments)
					ps.setString(21, aggregation[i].invoiceId)
					ps.setString(22, aggregation[i].orignalReference)
				},
				getBatchSize:
				{ aggregation.size() }
			] as BatchPreparedStatementSetter)
		}
		
		jdbcTemplate.update(DELETE_SQL);
		log.info("Deleted records which are older than 6 months")
	}
}