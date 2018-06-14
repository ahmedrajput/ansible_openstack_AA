package se.seamless.ers.components.dataaggregator.aggregator

import java.util.regex.Pattern

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.BatchPreparedStatementSetter
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.transaction.annotation.Transactional

import com.google.gson.Gson

import groovy.util.logging.Log4j


/**
 * Aggregates the purchases(credits) from a specific reseller type to a specific reseller type
 * e.g: purchases(credit_transfers) from operator to distributor 
 * Also aggregates debit transactions of the receiving reseller in above transaction.
 * e.g: debit transactions(topup) of distributor(this helps in calculation of balance's from reportUI
 *
 */
@Log4j
@DynamicMixin
public class StdResellerDetailedPurchaseReport extends AbstractAggregator{


	static final def TABLE = "std_reseller_detailed_purchases_aggregation"

	@Value('${StdResellerDetailedPurchaseReport.creditTransfersToResellerTypes:tamanga,vendor}')
	def toResellerTypes

	@Value('${StdResellerDetailedPurchaseReport.creditTransfersFromResellerIds:MM2ERS,TAM01}')
	def fromResellerIds

	@Value('${StdResellerDetailedPurchaseReport.transferInTransactionProfiles:{CREDIT_TRANSFER:[MM_TRANSFER]}}')
	def transferInProfiles

	@Value('${StdResellerDetailedPurchaseReport.transferOutTransactionProfiles:TOPUP}')
	def transferOutProfiles

	@Value('${StdResellerDetailedPurchaseReport.commissionPercent:3}')
	BigDecimal commissionPercent

	@Value('${StdResellerDetailedPurchaseReport.batch:1000}')
	int limit

	@Autowired
	@Qualifier("refill")
	private JdbcTemplate refill



	@Transactional
	@Scheduled(cron  = '${StdResellerDetailedPurchaseReport.cron:0 30 23 * * ?}')
	public void aggregate() {
		def transactions = getTransactions(limit)
		if (transactions) {

			log.info("Started Aggregation for StdResellerDetailedPurchaseReport")
			def transferInTxn = allowedProfiles(transactions, transferInProfiles)
					.findAll(successfulTransactions)

			log.debug("Transfer In TransactionCount "+transferInTxn.size())

			def resellerSpecificInTxn = findTransactionsFromResellerIds(transferInTxn,fromResellerIds)
					.findAll(toReceiverResellerTypes)
					.collect(transferInTransactionInfo)
					.findAll()
			log.debug("Reseller Specific Transfer In Transactions Count "+resellerSpecificInTxn.size())

			def transferOutTxn = findAllowedProfiles(transactions, transferOutProfiles)
					.findAll(successfulTransactions)
					
			log.debug("Debit Transactions Count "+transferOutTxn.size())

			def resellerSpecificOutTxn = findResellerTypeTransactions(transferOutTxn,toResellerTypes)
					.collect(transferOutTransactionInfo)
					.findAll()
			log.debug("Reseller Specific Transfer Out Transactions Count "+resellerSpecificOutTxn.size())

			updateAggregation(resellerSpecificInTxn, resellerSpecificOutTxn)
			updateCursor(transactions)
			schedule()
		}
	}

	private def allowedProfiles = {transactions , profiles ->
		Gson gson = new Gson()
		HashMap<String, List<String>> map = (HashMap<String, List<String>>)gson.fromJson(profiles, HashMap.class)
		transactions.findAll{
			if(it != null && map.keySet().contains(it.getProfile())){
				if(map.get(it.getProfile()) == null || map.get(it.getProfile()).size()==0)
					return it
				else if(map.get(it.getProfile()).contains(it.getErsTransaction().getProductId())){
					return it
				}
			}
		}
	}

	private def toReceiverResellerTypes = {
		it != null && Pattern.compile(Pattern.quote(it.getErsTransaction()?.getReceiverPrincipal()?.getResellerTypeId()?:"NoResellerTypeId"), Pattern.CASE_INSENSITIVE).matcher(toResellerTypes)
	}

	private def findTransactionsFromResellerIds = { transactions,resellerIds ->
		transactions.findAll {
			it != null && Pattern.compile(Pattern.quote(it.getErsTransaction()?.getSenderPrincipal()?.getResellerId()?:"NoResellerId"), Pattern.CASE_INSENSITIVE).matcher(resellerIds)
		}
	}
	/*
	 * Following definition finds all the transactions, whose initiator is of specific resellerType i.e tamanga,subdistributor etc
	 * This is used for fetching resellerType specific transfer out transactions(say topup initiated by tamanga etc)
	 */
	private def findResellerTypeTransactions = { transactions,resellerTypes ->
		transactions.findAll {
			it != null && Pattern.compile(Pattern.quote(it.getErsTransaction()?.getSenderPrincipal()?.getResellerTypeId()?:"NoResellerTypeId"), Pattern.CASE_INSENSITIVE).matcher(resellerTypes)
		}
	}

	private def transferInTransactionInfo = {

		log.debug("Processing Transaction:${it}")
		def tr = parser.parse(it.getJSON()).getAsJsonObject()
		log.debug("TransactionData --- "+tr)

		def ersReference = asString(tr, "ersReference")
		log.debug("ERS Reference for agent visit "+ersReference)

		double calculatedCommission = new BigDecimal(it.getErsTransaction().getRequestedTransferAmount().getValue()).multiply(commissionPercent*0.01).doubleValue();
		if(commissionPercent > 0 && calculatedCommission == 0.0)
			log.error("Error in calculating the commission")

		def resellerBalance = getSpecifiedBalance(tr,"Receiver")

		def endTime = it.getErsTransaction().getEndTime()

		[
			parentId				: it.getErsTransaction().getReceiverPrincipal()?.getParentResellerId()?:"Not Available",
			parentName				: it.getErsTransaction().getReceiverPrincipal()?.getParentResellerName()?:"Not Available",
			resellerId				: it.getErsTransaction().getReceiverPrincipal()?.getResellerId()?:"Not Available",
			resellerName			: it.getErsTransaction().getReceiverPrincipal()?.getResellerName()?:"Not Available",
			resellerMSISDN			: it.getErsTransaction().getReceiverPrincipal()?.getResellerMSISDN()?:"Not Available",
			resellerTransferIn		: it.getErsTransaction().getReceivedAmount()?.getValue()?:"0.00",
			commissionEarned		: calculatedCommission,
			openingBalance			: resellerBalance.balanceBefore,
			closingBalance			: resellerBalance.balanceAfter,
			transactionDate			: toSqlTimestamp(endTime),
			ersReference			: ersReference,
			commissionPercentage	: commissionPercent

		]
	}

	private def transferOutTransactionInfo = {

		log.debug("Processing Transaction:${it}")
		def tr = parser.parse(it.getJSON()).getAsJsonObject()
		log.debug("TransactionData --- "+tr)

		def ersReference = asString(tr, "ersReference")
		log.debug("ERS Reference for agent visit "+ersReference)

		def resellerBalance = getSpecifiedBalance(tr,"Sender")

		def endTime = it.getErsTransaction().getEndTime()

		[
			parentId				: it.getErsTransaction().getSenderPrincipal()?.getParentResellerId()?:"Not Available",
			parentName				: it.getErsTransaction().getSenderPrincipal()?.getParentResellerName()?:"Not Available",
			resellerId				: it.getErsTransaction().getSenderPrincipal()?.getResellerId()?:"Not Available",
			resellerName			: it.getErsTransaction().getSenderPrincipal()?.getResellerName()?:"Not Available",
			resellerMSISDN			: it.getErsTransaction().getSenderPrincipal()?.getResellerMSISDN()?:"Not Available",
			resellerTransferOut		: it.getErsTransaction().getRequestedTopupAmount()?.getValue()?:"0.00",
			openingBalance			: resellerBalance.balanceBefore,
			closingBalance			: resellerBalance.balanceAfter,
			transactionDate			: toSqlTimestamp(endTime),
			ersReference			: ersReference
		]
	}

	def getSpecifiedBalance = { tr, principalType ->

		tr.get("transactionRows")?.getAsJsonArray()?.findResult {
			if (principalType.equals(asString(it, "principalType"))) {
				[
					balanceBefore : it.get("balanceBefore").get("value"),
					balanceAfter  : it.get("balanceAfter").get("value")
				]
			}
		}
	}

	private def updateAggregation(List resellerSpecificInTxn, List resellerSpecificOutTxn) {
		if(resellerSpecificInTxn && resellerSpecificInTxn.size() != 0) {
			log.info("Aggregated Reseller Credit transactions : "+resellerSpecificInTxn.size())
			def sql = "INSERT INTO ${TABLE} (parentId, parentName, resellerId, resellerName, resellerMSISDN, resellerTransferIn, commissionEarned, openingBalance, closingBalance, transactionDate, ersReference, commissionPercentage) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
			def batchUpdate = jdbcTemplate.batchUpdate(sql, [
				setValues: { ps, i ->
					def row = resellerSpecificInTxn[i]
					def index = 0
					ps.setString(++index, row.parentId)
					ps.setString(++index, row.parentName)
					ps.setString(++index, row.resellerId)
					ps.setString(++index, row.resellerName)
					ps.setString(++index, row.resellerMSISDN)
					ps.setDouble(++index, row.resellerTransferIn)
					ps.setDouble(++index, row.commissionEarned)
					ps.setDouble(++index, row.get("openingBalance").getAsDouble())
					ps.setDouble(++index, row.get("closingBalance").getAsDouble())
					ps.setTimestamp(++index, row.transactionDate)
					ps.setString(++index, row.ersReference)
					ps.setDouble(++index, row.commissionPercentage)
				},
				getBatchSize: { resellerSpecificInTxn.size() }
			] as BatchPreparedStatementSetter)
		}
		if(resellerSpecificOutTxn && resellerSpecificOutTxn.size() != 0){
			log.info("Aggregated Reseller Debit Transactions :"+resellerSpecificOutTxn.size())
			def sql = "INSERT INTO ${TABLE} (parentId, parentName, resellerId, resellerName, resellerMSISDN, resellerTransferOut, openingBalance, closingBalance, transactionDate, ersReference) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
			def batchUpdate = jdbcTemplate.batchUpdate(sql, [
				setValues: { ps, i ->
					def row = resellerSpecificOutTxn[i]
					def index = 0
					ps.setString(++index, row.parentId)
					ps.setString(++index, row.parentName)
					ps.setString(++index, row.resellerId)
					ps.setString(++index, row.resellerName)
					ps.setString(++index, row.resellerMSISDN)
					ps.setDouble(++index, row.resellerTransferOut)
					ps.setDouble(++index, row.get("openingBalance").getAsDouble())
					ps.setDouble(++index, row.get("closingBalance").getAsDouble())
					ps.setTimestamp(++index, row.transactionDate)
					ps.setString(++index, row.ersReference)
				},
				getBatchSize: { resellerSpecificOutTxn.size() }
			] as BatchPreparedStatementSetter)
		}
	}
}
