package se.seamless.ers.components.dataaggregator.aggregator

import groovy.time.TimeCategory
import groovy.util.logging.Log4j
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.BatchPreparedStatementSetter
import org.springframework.jdbc.core.ColumnMapRowMapper
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.PreparedStatementSetter
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.transaction.annotation.Transactional

import java.text.MessageFormat

/**
 * Created by kranthikumar on 11/04/17.
 */
@Log4j
@DynamicMixin
public class StdSalesVisitTransferReport extends AbstractAggregator {

    static final def TABLE = "std_sales_visit_transfer_aggregation"

    @Value('${StdSalesVisitTransferReport.transferInProfiles:CREDIT_TRANSFER}')
    String transferInProfiles

    @Value('${StdSalesVisitTransferReport.transferOutProfiles:REGISTER_NO_STOCK_SALE}')
    String transferOutProfiles

    @Value('${StdSalesVisitTransferReport.batch:1000}')
    int limit

    @Autowired
    @Qualifier("regionsDb")
    private JdbcTemplate regionsDb


    @Transactional
    @Scheduled(cron = '${StdSalesVisitTransferReport.cron:0 0 0 * * ?}')
    @Override
    public void aggregate() {

        log.debug("Running Aggregate:: StdSalesVisitTransferReport")
        def allowedProfiles = [transferInProfiles, transferOutProfiles]

        def transactions = getTransactions(limit)
        if (transactions) {

            log.info(MessageFormat.format("Found {0} number of transactions for allowed profiles {1}.",
                    transactions.size(), allowedProfiles))

            List resellerTransactions = findAllowedProfiles(transactions, allowedProfiles)
                    .findAll(successfulTransactions)
                    .collect(transactionInformation)
                    .findAll()

            updateAggregation(resellerTransactions)
            updateCursor(transactions)
            schedule()
        } else {
            log.debug(MessageFormat.format("No transactions found for allowed profiles {0}", allowedProfiles))
        }
    }

    private def transactionInformation = {

        log.debug("Processing Sales Visit Transfer transaction: ${it}")
        def tr = parser.parse(it.getJSON()).getAsJsonObject()
        log.debug("TransactionData --- "+tr)


        def receiverPrincipal = it.getErsTransaction()?.getReceiverPrincipal()
        def senderPrincipal = it.getErsTransaction()?.getSenderPrincipal()

        def transProp = it.getErsTransaction()?.getTransactionProperties()

        def resellerId = receiverPrincipal?.getResellerId()

        def resellerMSISDN = receiverPrincipal?.getResellerMSISDN()

        def agentMSISDN = senderPrincipal?.getResellerMSISDN()

        def resellerName = receiverPrincipal?.getResellerName()

        def agentName = senderPrincipal?.getResellerName()

        def resellerType = receiverPrincipal?.getResellerTypeName()

        def resellerCellId = it.getErsTransaction()?.getTransactionProperties()?.get("RECEIVER_CELLID")

        def resellerRegionInfo = getRegionInformation(resellerCellId)

        def agentCellId = it.getErsTransaction()?.getTransactionProperties()?.get("INITIATOR_CELLID")

        def agentRegionInfo = getRegionInformation(agentCellId)

        def profileId = it.getErsTransaction()?.getProfileId()


        def saleType
        if(profileId == "CREDIT_TRANSFER") {
            saleType="Sale"
        } else {
            saleType= it.getErsTransaction()?.getTransactionProperties()?.get("NO_STOCK_REASON")
        }

        def lowStockThresholdAmount = extractAmount(transProp, "THRESHOLD_STOCK")

        def suggestedStockToPurchase = extractAmount(transProp, "SUGGESTED_STOCK")

        def stockAmountPurchased = (profileId == "CREDIT_TRANSFER") ? it.getErsTransaction()?.getRequestedTransferAmount()?.getValue() : new BigDecimal(0)

        def resellerBalance = getSpecifiedBalance(tr,"Receiver")

        def beforeBalance = resellerBalance?.balanceBefore?.getAsBigDecimal()

        def afterBalance = resellerBalance?.balanceAfter?.getAsBigDecimal()

        if (profileId == "REGISTER_NO_STOCK_SALE") {
            beforeBalance = extractAmount(transProp, "CURRENT_STOCK")
            afterBalance = beforeBalance
        }

        def endTime = it.getErsTransaction()?.getEndTime()

        def ersReference = it.getErsTransaction()?.getErsReference()

        [
                resellerId				: resellerId,
                resellerMSISDN			: resellerMSISDN,
                agentMSISDN			    : agentMSISDN,
                resellerName            : resellerName,
                agentName               : agentName,
                resellerType			: resellerType?:"Not Available",
                resellerRegion		  	: resellerRegionInfo?.regionName?:"Not Availalble",
                resellerLocation	  	: resellerRegionInfo?.location?:"Not Available",
                resellerZone		  	: resellerRegionInfo?.Zone?:"Not Available",
                agentRegion		      	: agentRegionInfo?.regionName?:"Not Available",
                agentLocation	  	    : agentRegionInfo?.location?:"Not Available",
                agentZone		  	    : agentRegionInfo?.Zone?:"Not Available",
                saleType                : saleType,
                lowStockThresholdAmount : lowStockThresholdAmount,
                balanceBefore           : beforeBalance,
                suggestedStockToPurchase: suggestedStockToPurchase,
                stockAmountPurchased    : stockAmountPurchased,
                balanceAfter            : afterBalance,
                transactionDate			: toSqlDate(endTime),
                ersReference            : ersReference,
        ]
    }


    private def BigDecimal extractAmount(prop, type) {
        def fVal = new BigDecimal(0)
        String val = prop?.get(type)
        try {
            fVal = new BigDecimal(val)
        } catch (NumberFormatException e1) {
            log.warn(MessageFormat.format("Given value {0} for {1} is not valid", val, type))
        } catch (NullPointerException e2) {
            log.warn(MessageFormat.format("Given value {0} for {1} is not valid", val, type))
        }
        return fVal
    }

    def getSpecifiedBalance = { tr, principalType ->

        tr.get("transactionRows")?.getAsJsonArray()?.findResult {
            if (principalType == asString(it, "principalType")) {
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

        if(cellId != null)
            regionId = regionsDb.query(CELLS_SQL, [setValues : { ps ->
                use(TimeCategory) {
                    ps.setString(1, cellId)
                }
            }] as PreparedStatementSetter, new ColumnMapRowMapper())

        if(regionId !=null && regionId.size() != 1){
            log.error("Error in getting RegionId for given cellid: "+cellId)
            return null
        }

        def reg = regionId.get(0)

        def regionInfo = regionsDb.query(REGIONS_SQL, [setValues : { ps ->
            use(TimeCategory) {
                ps.setString(1, reg.get("regionId"))
            }
        }] as PreparedStatementSetter, new ColumnMapRowMapper())

        if(regionInfo == null || regionInfo.size() != 1)
            log.error("Error in getting Reseller Region Information for given cellid: "+cellid)

        return regionInfo[0]
    }
    private updateAggregation(List transactions){

        def insertSql = "INSERT INTO ${TABLE} (resellerId, resellerMSISDN, agentMSISDN, resellerName, agentName, " +
                "resellerType, resellerRegion, resellerLocation, resellerZone, agentRegion, agentLocation, agentZone, " +
                "saleType, lowStockThresholdAmount, balanceBefore, suggestedStockToPurchase, stockAmountPurchased, " +
                "balanceAfter, transactionDate, ersReference) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"

        if(transactions && ! transactions.isEmpty()){

            log.info("Insertion data size : "+transactions.size())
            int index = 0

            jdbcTemplate.batchUpdate(insertSql, [
                    setValues   : { ps, i ->
                        index = 0;
                        def t = transactions.get(i);
                        ps.setString(++index, t.resellerId)
                        ps.setString(++index, t.resellerMSISDN)
                        ps.setString(++index, t.agentMSISDN)
                        ps.setString(++index, t.resellerName)
                        ps.setString(++index, t.agentName)
                        ps.setString(++index, t.resellerType)
                        ps.setString(++index, t.resellerRegion)
                        ps.setString(++index, t.resellerLocation)
                        ps.setString(++index, t.resellerZone)
                        ps.setString(++index, t.agentRegion)
                        ps.setString(++index, t.agentLocation)
                        ps.setString(++index, t.agentZone)
                        ps.setString(++index, t.saleType)
                        ps.setBigDecimal(++index, t.lowStockThresholdAmount)
                        ps.setBigDecimal(++index, t.balanceBefore)
                        ps.setBigDecimal(++index, t.suggestedStockToPurchase)
                        ps.setBigDecimal(++index, t.stockAmountPurchased)
                        ps.setBigDecimal(++index, t.balanceAfter)
                        ps.setDate(++index,t.transactionDate)
                        ps.setString(++index, t.ersReference)
                    },
                    getBatchSize: { transactions.size() }
            ] as BatchPreparedStatementSetter)
        }
    }
}