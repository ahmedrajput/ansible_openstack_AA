package se.seamless.ers.components.dataaggregator.aggregator


import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.ColumnMapRowMapper
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.PreparedStatementSetter
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.transaction.annotation.Transactional

import groovy.time.TimeCategory
import groovy.util.logging.Log4j

import java.text.MessageFormat

/**
 * This report shows the summary of transactions of resellers at a given period of time.
 */
@Log4j
@DynamicMixin
public class StdCustomTransactionsSummaryAggregator extends AbstractAggregator{


	static final String TABLE = "std_custom_transaction_summary_aggregation"

	@Value('${StdCustomTransactionsSummaryAggregator.transferInProfiles:CREDIT_TRANSFER}')
	String transferInProfiles

	@Value('${StdCustomTransactionsSummaryAggregator.transferOutProfiles:TOPUP}')
	String transferOutProfiles

	@Value('${StdCustomTransactionsSummaryAggregator.batch:1000}')
	int limit

	@Autowired
	@Qualifier("regionsDb")
	private JdbcTemplate regionsDb


	@Transactional
	@Scheduled(cron = '${StdCustomTransactionsSummaryAggregator.cron:0 0 0 * * ?}')
	public void aggregate() {

		def allowedProfiles = [transferInProfiles, transferOutProfiles]

		log.debug("Running Aggregate:: StdCustomTransactionsSummaryAggregator")

		def transactions = getTransactions(limit)

		if (transactions) {
			log.info(MessageFormat.format("Found {0} number of transactions for allowed profiles {1}.",
					transactions.size(), allowedProfiles))

			List resellerTransactions = findAllowedProfiles(transactions, allowedProfiles)
					.findAll(successfulTransactions)
					.collect(gatherRequiredInfo)
					.findAll()

			updateAggregation(resellerTransactions)
			updateCursor(transactions)
			schedule()
		} else {
			log.debug(MessageFormat.format("No transactions found for allowed profiles {0}", allowedProfiles))
		}
	}
	
	private gatherRequiredInfo = {

		log.debug("Custom Transaction summary aggregator: ${it}")
		def tr = parser.parse(it.getJSON()).getAsJsonObject()
		log.debug("TransactionData --- "+tr)

		def resellerName = it.getErsTransaction()?.getPrincipal()?.getResellerName()

		def resellerCellId = it.getErsTransaction()?.getTransactionProperties()?.get("INITIATOR_CELLID")

		def resellerRegionInfo = getRegionInformation(resellerCellId)

		def resellerMSISDN = "Not Available"
		if(it.getErsTransaction()?.getPrincipal()?.getSubmittedPrincipalId()?.getType()?.equals("RESELLERMSISDN"))
			resellerMSISDN = it.getErsTransaction().getPrincipal().getSubmittedPrincipalId().getId()

		def resellerId = it.getErsTransaction()?.getPrincipal()?.getResellerId()
		log.debug("AgentId retrieved from transaction "+resellerId)

		def resellerType
		resellerType = it.getErsTransaction()?.getPrincipal()?.getResellerTypeName()
		if(resellerType == null){
			log.debug("Couldn't get ResellerType Name. Checking if ResellerTypeId exists")
			resellerType = it.getErsTransaction()?.getPrincipal()?.getResellerTypeId()
		}

		def resellerBalance = getSpecifiedBalance(tr,"Sender")

		def endTime = it.getErsTransaction().getEndTime()

		def transactionAmount

		if(transferInProfiles.contains(it?.getProfile()))
			transactionAmount = it.getErsTransaction().getReceivedAmount()?.getValue()?:"0.00"
		else
			transactionAmount = it.getErsTransaction().getRequestedTopupAmount()?.getValue()?:"0.00"
     if(resellerId!=null)
      {
		[
			resellerId				: resellerId,
			resellerMSISDN			: resellerMSISDN,
			resellerName			: resellerName,
			resellerType			: resellerType?:"Not Available",
			resellerRegion		  	: resellerRegionInfo?.regionName?:"Not Available",
			resellerLocation	  	: resellerRegionInfo?.location?:"Not Available",
			resellerZone		  	: resellerRegionInfo?.Zone?:"Not Available",
			transactionType			: it?.getProfile()?:"Not Available",
			transactionAmount		: transactionAmount,
			openingBalance			: resellerBalance?.balanceBefore?.getAsBigDecimal(),
			closingBalance			: resellerBalance?.balanceAfter?.getAsBigDecimal(),
			transactionDate			: toSqlDate(endTime),
		]

	  }
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

	private getRegionInformation(cellId) {

		if (cellId == null) {
			return null
		}

		def regionId = null
		def CELLS_SQL = "SELECT region AS regionId FROM cells WHERE id=?"
		def REGIONS_SQL = "SELECT name AS regionName, location AS location, cluster AS Zone FROM regions WHERE id=?"
		if (cellId != null)
			regionId = regionsDb.query(CELLS_SQL, [setValues: { ps ->
				use(TimeCategory) {
					ps.setString(1, cellId)
				}
			}] as PreparedStatementSetter, new ColumnMapRowMapper())

		if (regionId != null && regionId.size() != 1) {
			log.error("Error in getting RegionId for given cellid: " + cellId)
			return null
		}

		def reg = regionId.get(0)

		def regionInfo = regionsDb.query(REGIONS_SQL, [setValues: { ps ->
			use(TimeCategory) {
				ps.setString(1, reg.get("regionId"))
			}
		}] as PreparedStatementSetter, new ColumnMapRowMapper())

		if (regionInfo == null || regionInfo.size() != 1)
			log.error("Error in getting Reseller Region Information for given cellid: " + cellId)

		return regionInfo[0]
	}

	private updateAggregation(List resellerTransactions){
		if(resellerTransactions && ! resellerTransactions.isEmpty()){

			log.info("Insertion data size : "+resellerTransactions.size())

			def insertSql = "INSERT INTO ${TABLE} (resellerId, resellerMSISDN, resellerName, resellerType, resellerRegion," +
					" resellerLocation, resellerZone, transactionType, resellerOpeningBalance, totalTransactionAmount," +
					" resellerClosingBalance, transactionDate) values(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"

			def updateSql = "UPDATE ${TABLE} set totalTransactionAmount = ?, resellerClosingBalance = ?  WHERE resellerId=? AND resellerRegion=? AND " +
					" resellerLocation=? AND resellerZone=? AND transactionType=? AND transactionDate=?"

			for(def transaction : resellerTransactions) {

				def prevTransactionInfo = checkForExistingTransactions(transaction)
				//If yes then get the existing amount.
				if(prevTransactionInfo && prevTransactionInfo.size() > 0)
				{
					def updateTransactionAmount = prevTransactionInfo[0].totalTransactionAmount + transaction.transactionAmount
					jdbcTemplate.update(updateSql,
							[
									setValues: { ps ->
										int index = 0
										ps.setBigDecimal(++index, updateTransactionAmount)
										ps.setBigDecimal(++index, transaction.closingBalance)
										ps.setString(++index, transaction.resellerId)
										ps.setString(++index, transaction.resellerRegion)
										ps.setString(++index, transaction.resellerLocation)
										ps.setString(++index, transaction.resellerZone)
										ps.setString(++index, transaction.transactionType)
										ps.setDate(++index, transaction.transactionDate)
									}
							] as PreparedStatementSetter)
				}
				else{
					/*
					 first check if there is a transaction for that resellerId with transactionType and a transactionDate
					 if(yes)
					 then get the opening balance(means balanceBefore) for that day
					 else
					 directly insert the row
					 */

					//above query may result in retrieving many rows, but all the rows will have same value, so consider the first row value
					//also don't include transactionType in above query, coz even if it credit transfer or topup, opening balance should be same.
					def selectOpeningBalance = "SELECT resellerOpeningBalance from ${TABLE} where resellerId=? AND transactionDate=? "
					def balanceInfo = jdbcTemplate.query(selectOpeningBalance,
							[
									setValues: { ps ->
										int index = 0
										ps.setString(++index, transaction.resellerId)
										ps.setDate(++index, transaction.transactionDate)
									}
							]as PreparedStatementSetter, new ColumnMapRowMapper()
					)

					def openingBalance = transaction.openingBalance
					if (balanceInfo && balanceInfo.size() > 0) {
						openingBalance = balanceInfo[0].resellerOpeningBalance
					}

					jdbcTemplate.update(insertSql,
							[
									setValues: { ps ->
										int index = 0
										ps.setString(++index, transaction.resellerId)
										ps.setString(++index, transaction.resellerMSISDN)
										ps.setString(++index, transaction.resellerName)
										ps.setString(++index, transaction.resellerType)
										ps.setString(++index, transaction.resellerRegion)
										ps.setString(++index, transaction.resellerLocation)
										ps.setString(++index, transaction.resellerZone)
										ps.setString(++index, transaction.transactionType)
										ps.setBigDecimal(++index, openingBalance)
										ps.setBigDecimal(++index, transaction.transactionAmount)
										ps.setBigDecimal(++index, transaction.closingBalance)
										ps.setDate(++index, transaction.transactionDate)
									}
							] as PreparedStatementSetter)
				}

			}
		}
	}

	Object checkForExistingTransactions(transaction) {
		def selectSqlTransactionAmount = "SELECT totalTransactionAmount from ${TABLE} " +
				"WHERE resellerId=? AND resellerRegion=? AND resellerLocation=? AND resellerZone=? AND transactionType=? AND transactionDate=?"
		//Check if there is any existing summary record with for same (resellerId, resellerRegion, transactionType & transactionDate)
		def prevTransactionInfo = jdbcTemplate.query(selectSqlTransactionAmount,
				[
						setValues: { ps ->
							int index = 0
							ps.setString(++index, transaction.resellerId)
							ps.setString(++index, transaction.resellerRegion)
							ps.setString(++index, transaction.resellerLocation)
							ps.setString(++index, transaction.resellerZone)
							ps.setString(++index, transaction.transactionType)
							ps.setDate(++index, transaction.transactionDate)
						}
				]as PreparedStatementSetter, new ColumnMapRowMapper()
		)
		return prevTransactionInfo
	}
}


