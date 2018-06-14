package se.seamless.ers.components.dataaggregator.aggregator

import groovy.transform.EqualsAndHashCode
import groovy.transform.ToString
import groovy.util.logging.Log4j

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.BatchPreparedStatementSetter
import org.springframework.jdbc.core.ColumnMapRowMapper
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.transaction.annotation.Transactional


/**
 * Aggregates details of dealer's transactions.
 * Used by reports:
 * <ul>
 * <li></li>
 * </ul>
 *
 * @author Sabir
 */
@Log4j
@DynamicMixin
public class StdDealerDetailsAggregator extends AbstractAggregator
{
	static final def TABLE = "std_dealer_detail_aggregation"
	static final def BALANCE = """SELECT
		c.name AS resellerName,
		c.tag AS resellerId,
		dev.address AS resellerMSISDN,
		c.reseller_path AS resellerPath,
		0 AS transferIn,
		0 AS totalPinlessTopups,
		0 AS totalPinTopups,
		c.time_created AS date,
		b.balance AS currentBalance
		FROM accounts.accounts b
		JOIN Refill.pay_prereg_accounts p ON (b.accountId = p.account_nr)
		JOIN Refill.commission_receivers c ON (p.owner_key = c.receiver_key)
		JOIN Refill.extdev_devices dev ON (c.receiver_key = dev.owner_key)
		JOIN Refill.commission_contracts d ON (d.contract_key = c.contract_key);
		"""
	@Value('${StdDealerDetailsAggregator.allowed_profiles:TOPUP,CREDIT_TRANSFER,REVERSE_CREDIT_TRANSFER,SUPPORT_TRANSFER,PURCHASE}')
	String allowed_profiles
	
	def allowedProfiles = []
	
	@Value('${StdDealerDetailsAggregator.transfer_in_profiles:CREDIT_TRANSFER,SUPPORT_TRANSFER}')
	String transferInAllowedProfiles
	def transferInProfiles = []
	
	@Value('${StdDealerDetailsAggregator.topup_profiles:TOPUP}')
	String topupAllowedProfiles
	def topupProfiles = []
	
	@Value('${StdDealerDetailsAggregator.purchase_profiles:PURCHASE}')
	String purchaseAllowedProfiles
	def purchaseProfiles = []
	
	@Value('${StdDealerDetailsAggregator.reversal_profiles:REVERSE_CREDIT_TRANSFER}')
	String reversalAllowedProfiles
	def reversalProfiles = []

	@Value('${StdDealerDetailsAggregator.batch:1000}')
	int limit
	
	@Value('${StdDealerDetailsAggregator.enableFetch:false}')
	private boolean enableFetch
	
	@Autowired
	@Qualifier("refill")
	private JdbcTemplate refill
	
	
	@EqualsAndHashCode
	@ToString
	private static class Key
	{
		Date date
		String resellerId
		String resellerMSISDN
		String resellerPath
		String resellerName
		String parentResellerId
		BigDecimal transferIn
		BigDecimal totalPinlessTopups
		BigDecimal totalPinTopups
		BigDecimal currentBalance

	}
	@Transactional
	@Scheduled(cron = '${StdDealerDetailsAggregator.cron:0 0/30 * * * ?}')
	public void aggregate()
	{
		allowedProfiles = []
		
		if(allowed_profiles){
			def allowedProfileList = allowed_profiles.split(",")
			for (resellerProfile in allowedProfileList) {
					allowedProfiles.add(resellerProfile.asType(String))
			}
		}
		
		transferInProfiles = []
		
		if(transferInAllowedProfiles){
			def allowedProfileList = transferInAllowedProfiles.split(",")
			for (resellerProfile in allowedProfileList) {
					transferInProfiles.add(resellerProfile.asType(String))
			}
		}
		
		topupProfiles = []
		
		if(topupAllowedProfiles){
			def allowedProfileList = topupAllowedProfiles.split(",")
			for (resellerProfile in allowedProfileList) {
					topupProfiles.add(resellerProfile.asType(String))
			}
		}
		
		purchaseProfiles = []
		
		if(purchaseAllowedProfiles){
			def allowedProfileList = purchaseAllowedProfiles.split(",")
			for (resellerProfile in allowedProfileList) {
					purchaseProfiles.add(resellerProfile.asType(String))
			}
		}
		
		reversalProfiles = []
		
		if(reversalAllowedProfiles){
			def allowedProfileList = reversalAllowedProfiles.split(",")
			for (resellerProfile in allowedProfileList) {
					reversalProfiles.add(resellerProfile.asType(String))
			}
		}
		
		if(enableFetch)
		{
			def resellers = refill.query(BALANCE,new ColumnMapRowMapper())
			
			log.info("Got StdDealerDetailsAggregator ${resellers.size()} activate reseller from refill.")
			def sqlReseller="""
			INSERT INTO ${TABLE} 
			(resellerId, transactions_date, resellerMSISDN, ResellerName, transferIn, total_pinless_topups, total_pin_topups, current_balance, reseller_path) 
			VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
			ON DUPLICATE KEY UPDATE 
			transferIn=transferIn+VALUES(transferIn),
			total_pinless_topups=total_pinless_topups+VALUES(total_pinless_topups),
			total_pin_topups=total_pin_topups+VALUES(total_pin_topups),
			current_balance=VALUES(current_balance),
			reseller_path=VALUES(reseller_path),
			ResellerName=VALUES(ResellerName)
			"""
			if(resellers) {log.info("reseller exits")
				jdbcTemplate.batchUpdate(sqlReseller,[
					setValues: { ps, i ->
						ps.setString(1, resellers[i].resellerId)
						ps.setDate(2,toSqlDate(resellers[i].date))
						ps.setString(3,resellers[i].resellerMSISDN)
						ps.setString(4,resellers[i].resellerName)
						ps.setBigDecimal(5,resellers[i].transferIn)
						ps.setBigDecimal(6,resellers[i].totalPinlessTopups)
						ps.setBigDecimal(7,resellers[i].totalPinTopups)
						ps.setBigDecimal(8,resellers[i].currentBalance)
						ps.setString(9,resellers[i].resellerPath)
					},
				getBatchSize: { resellers.size() }
					] as BatchPreparedStatementSetter)
				log.info("Data inserted in StdDealerDetailsAggregator")
			}
			enableFetch = false;
		}
		
		def transactions = getTransactions(limit)
		if (transactions)
		{
			def aggregatedTransactions = findAllowedProfiles(transactions, allowedProfiles)
					.findAll(successfulTransactions)
					
			def aggregation = aggregatedTransactions
					.collect(transactionInfo)
					.findAll()
					.groupBy(key)
					.collect(statistics)
			def aggregation1 = aggregatedTransactions
					.collect(transactionInfo1)
					.findAll()
					.groupBy(key)
					.collect(statistics)
			log.info("1st aggregated into ${aggregation.size()} rows.")
			log.info("2nd aggregated into ${aggregation1.size()} rows.")
			aggregation.addAll(aggregation1)
			updateAggregation(aggregation)
			updateCursor(transactions)
			schedule()
		}
	}
	private def transactionInfo =
			{
				def tr = parser.parse(it.getJSON()).getAsJsonObject()
				
				def profileId = it.profile
				def date = it.getStartTime().clone().clearTime()
				
				def resellerId = null
				def resellerName = null
				def resellerMSISDN = null
				def resellerParent = null
				def resellerPath = null
				def transferIn = 0
				def totalPinlessTopups = 0
				def totalPinTopups = 0
				def currentBalance = 0
				def senderData = findSenderPrincipalAlongWithTransactionRow(tr)
				
				if(transferInProfiles.contains(profileId))
				{
					
					def receiverData = findReceiverPrincipalAlongWithTransactionRow(tr)
					resellerId = asString(receiverData.principal, "resellerId")
					resellerName = asString(receiverData.principal, "resellerName")
					resellerMSISDN = asString(receiverData.principal, "resellerMSISDN")
					resellerParent = asString(receiverData.principal, "parentResellerId")
					resellerPath = asString(receiverData.principal, "resellerPath")
					transferIn = getBigDecimalFieldFromAnyField(receiverData.transactionRow, "value", "amount")?.abs()
					totalPinTopups=0
					totalPinlessTopups=0
					currentBalance = getBigDecimalFieldFromAnyField(receiverData.transactionRow, "value", "balanceAfter")
				}
				else if(topupProfiles.contains(profileId))
				{
					
					resellerId = asString(senderData.principal, "resellerId")
					resellerName = asString(senderData.principal, "resellerName")
					resellerMSISDN = asString(senderData.principal, "resellerMSISDN")
					resellerParent = asString(senderData.principal, "parentResellerId")
					resellerPath = asString(senderData.principal, "resellerPath")
					totalPinlessTopups = getBigDecimalFieldFromAnyField(senderData.transactionRow, "value", "amount")?.abs()
					totalPinTopups=0
					transferIn = 0
					currentBalance = getBigDecimalFieldFromAnyField(senderData.transactionRow, "value", "balanceAfter")
				}
				else if(purchaseProfiles.contains(profileId))
				{
					resellerId = asString(senderData.principal, "resellerId")
					resellerName = asString(senderData.principal, "resellerName")
					resellerMSISDN = asString(senderData.principal, "resellerMSISDN")
					resellerParent = asString(senderData.principal, "parentResellerId")
					resellerPath = asString(senderData.principal, "resellerPath")
					totalPinlessTopups=0
					totalPinTopups = getBigDecimalFieldFromAnyField(senderData.transactionRow, "value", "amount")?.abs()
					transferIn = 0
					currentBalance = getBigDecimalFieldFromAnyField(senderData.transactionRow, "value", "balanceAfter")
				}
				else if(reversalProfiles.contains(profileId))
				{
					resellerId = asString(senderData.principal, "resellerId")
					resellerName = asString(senderData.principal, "resellerName")
					resellerMSISDN = asString(senderData.principal, "resellerMSISDN")
					resellerParent = asString(senderData.principal, "parentResellerId")
					resellerPath = asString(senderData.principal, "resellerPath")
					totalPinlessTopups=0
					totalPinTopups = 0
					//Receiver in case of reversal
					transferIn = getBigDecimalFieldFromAnyField(senderData.transactionRow, "value", "amount")?.abs()
					currentBalance = getBigDecimalFieldFromAnyField(senderData.transactionRow, "value", "balanceAfter")
				}
				if(resellerId)
				[
						date			: date,
						resellerId      : resellerId,
						resellerMSISDN  : resellerMSISDN,
						resellerPath    : resellerPath,
						resellerName    : resellerName,
						parentResellerId: resellerParent,
						transferIn		: transferIn,
						totalPinlessTopups     : totalPinlessTopups,
						totalPinTopups	: totalPinTopups,
						currentBalance	: currentBalance
				]
			}
			private def transactionInfo1 =
			{
				log.debug("Processing transaction: ${it}")
				def tr = parser.parse(it.getJSON()).getAsJsonObject()
				log.debug("TransactionData --- "+tr)
				
				def profileId = it.profile
				def date = it.getStartTime().clone().clearTime()
				
				def resellerId = null
				def resellerName = null
				def resellerMSISDN = null
				def resellerParent = null
				def resellerPath = null
				def transferIn = 0
				def totalPinlessTopups = 0
				def totalPinTopups = 0
				def currentBalance = 0
				def senderData = findSenderPrincipalAlongWithTransactionRow(tr)
				
				if(transferInProfiles.contains(profileId))
				{
					
					resellerId = asString(senderData.principal, "resellerId")
					resellerName = asString(senderData.principal, "resellerName")
					resellerMSISDN = asString(senderData.principal, "resellerMSISDN")
					resellerParent = asString(senderData.principal, "parentResellerId")
					resellerPath = asString(senderData.principal, "resellerPath")
					transferIn = 0
					totalPinTopups=0
					totalPinlessTopups=0
					currentBalance = getBigDecimalFieldFromAnyField(senderData.transactionRow, "value", "balanceAfter")
				}
				
				else if(reversalProfiles.contains(profileId))
				{
					def receiverData = findReceiverPrincipalAlongWithTransactionRow(tr)
					resellerId = asString(receiverData.principal, "resellerId")
					resellerName = asString(receiverData.principal, "resellerName")
					resellerMSISDN = asString(receiverData.principal, "resellerMSISDN")
					resellerParent = asString(receiverData.principal, "parentResellerId")
					resellerPath = asString(receiverData.principal, "resellerPath")
					//Sender in case of reversal
					transferIn = getBigDecimalFieldFromAnyField(receiverData.transactionRow, "value", "amount")?.abs().negate()
					totalPinTopups=0
					totalPinlessTopups=0
					currentBalance = getBigDecimalFieldFromAnyField(receiverData.transactionRow, "value", "balanceAfter")
				}
				if(resellerId)
				[
						date			: date,
						resellerId      : resellerId,
						resellerMSISDN  : resellerMSISDN,
						resellerPath    : resellerPath,
						resellerName    : resellerName,
						parentResellerId: resellerParent,
						transferIn		: transferIn,
						totalPinlessTopups     : totalPinlessTopups,
						totalPinTopups	: totalPinTopups,
						currentBalance	: currentBalance
				]
			}

	private def key =
			{
				[
						date                  : it.date,
						resellerId            : it.resellerId,
						resellerMSISDN        : it.resellerMSISDN,
						resellerPath          : it.resellerPath,
						resellerName       	  : it.resellerName,
						parentResellerId	  : it.resellerParent,
						transferIn			  : it.transferIn,
						totalPinlessTopups    : it.totalPinlessTopups,
						totalPinTopups		  : it.totalPinTopups,
						currentBalance        : it.currentBalance
				]
			}

	private def statistics =
			{	key, list ->
				[
						key : key
				]
			}

	private def updateAggregation(List aggregation)
	{
		log.info("Aggregated into ${aggregation.size()} rows.")
		if(aggregation)
		{
			def sql = """
			INSERT INTO ${TABLE} 
			(resellerId, transactions_date, resellerMSISDN, ResellerName, transferIn, total_pinless_topups, total_pin_topups, current_balance, reseller_path) 
			VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
			ON DUPLICATE KEY UPDATE 
			transferIn=transferIn+VALUES(transferIn),
			total_pinless_topups=total_pinless_topups+VALUES(total_pinless_topups),
			total_pin_topups=total_pin_topups+VALUES(total_pin_topups),
			current_balance=VALUES(current_balance),
			reseller_path=VALUES(reseller_path),
			ResellerName=VALUES(ResellerName)
			"""
			def batchUpdate = jdbcTemplate.batchUpdate(sql, [
					setValues:
							{ ps, i ->
								def row = aggregation[i]
								int index=0
								ps.setString(++index, row.key.resellerId)
								ps.setDate(++index,toSqlDate(row.key.date))
								ps.setString(++index,row.key.resellerMSISDN)
								ps.setString(++index,row.key.resellerName)
								ps.setBigDecimal(++index,row.key.transferIn)
								ps.setBigDecimal(++index,row.key.totalPinlessTopups)
								ps.setBigDecimal(++index,row.key.totalPinTopups)
								ps.setBigDecimal(++index,row.key.currentBalance)
								ps.setString(++index,row.key.resellerPath)
								
							},
					getBatchSize:
							{ aggregation.size() }
			] as BatchPreparedStatementSetter)
		}
	}
}