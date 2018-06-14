package se.seamless.ers.components.dataaggregator.aggregator

import groovy.util.logging.Log4j
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.BatchPreparedStatementSetter
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.transaction.annotation.Transactional

/**
 * Keeps the record of the last sold vouchers by voucher on demand feature.
 *
 * @author Danish Amjad
 */
@Log4j
@DynamicMixin
public class StdVoucherOnDemandAggregator extends AbstractAggregator {
    private static final def TABLE = "std_voucher_on_demand_aggregation"
    private static final def VOT_PURCHASE = "VOT_PURCHASE"
    private static final def VOS_PURCHASE = "VOS_PURCHASE"
    private static final def VOUCHER_REDEEM = "VOUCHER_REDEEM"
    private static final def BLOCK_VOUCHER = "BLOCK_VOUCHER"
    private static final def UNBLOCK_VOUCHER = "UNBLOCK_VOUCHER"

    @Value('${StdVoucherOnDemandAggregator.batch:1000}')
    int limit

    @Value('${StdVoucherOnDemandAggregator.voucher.expiry.format:dd-MM-yyyy}')
    String voucherExpiryFormat
    //TODO: remove this format after coordinating with the TXE guy @sajjad.pervaiz
    String voucherExpirySecondaryFormat = "EEE MMM dd hh:mm:ss zzz yyyy"


    enum VOUCHER_STATUS {
        SOLD(3), REDEEMED(4), BLOCKED(6)

        int statusId

        VOUCHER_STATUS(int statusId) {
            this.statusId = statusId;
        }

        int getStatusId() {
            return statusId;
        }

        void setStatusId(int statusId) {
            this.statusId = statusId;
        }
    }


    static final def ALLOWED_PROFILES = [
            VOUCHER_REDEEM,
            VOT_PURCHASE,
            VOS_PURCHASE,
            BLOCK_VOUCHER,
            UNBLOCK_VOUCHER
    ]

    @Transactional
    @Scheduled(cron = '${StdVoucherOnDemandAggregator.cron:0 * * * * *}')
    public void aggregate() {
        log.info("Started aggregation ...")
        int aggregationCount = 0;
        def transactions = getTransactions(limit)

        if (transactions) {
            def aggregation = findAllowedProfiles(transactions, ALLOWED_PROFILES)
                    .findAll(successfulTransactions)
                    .collect(transactionInfoHashMap)
            List filteredList = prepareAggregationForInsertion(aggregation)
            aggregationCount = filteredList?.size()
            updateAggregation(filteredList)
            updateCursor(transactions)
            schedule()
        }
        log.info("Ended aggregation. ${aggregationCount} transactions are aggregated!")
    }


    private def transactionInfoHashMap =
            {

                def tr = parser.parse(it.getJSON())?.getAsJsonObject()
                log.debug("JSON received - " + tr.toString())

                def propsMap = tr.get("resultProperties")?.getAsJsonObject()?.
                        get("map")?.getAsJsonObject()
                def serialNo = asString(propsMap, "voucherSerial")
                def voucherCode = asString(propsMap, "voucherPin")?: getValueFromPathAsString(tr, "transactionProperties.map.voucherPin")
                def priceValue = collectAmount(tr, it.profile)
                def priceCurrency = collectCurrency(tr)
                def date = propsMap.get("voucherExpiry")?.getAsString()
                def expiryDate = null
                if (date) {
                    expiryDate = parseDate(date)
                }

                def ersReference = tr.get("ersReference")?.getAsString()
                def subscriberMSISDN = getReceiverMSISDN(tr)
                def resellerMSISDN = getSenderMSISDN(tr)
                def clientReference = getValueFromPathAsString(tr, "transactionProperties.map.clientReference")

                //HashMap
                [
                        serialNo        : serialNo,
                        voucherCode     : voucherCode,
                        priceValue      : priceValue,
                        priceCurrency   : priceCurrency,
                        expiryDate      : expiryDate,
                        subscriberMSISDN: subscriberMSISDN,
                        resellerMSISDN  : resellerMSISDN,
                        ersReference    : ersReference,
                        profileId       : it.getProfile(),
                        clientReference : clientReference

                ]


            }

    private def prepareAggregationForInsertion(List aggregation) {
        log.debug("Preparing aggregation. List: " + aggregation)
        List filteredList = new ArrayList();
        if (aggregation) {
            for (def item : aggregation) {
                if (null != item.serialNo) {
                    switch (item.profileId) {
                        case VOT_PURCHASE:
                        case VOS_PURCHASE:
                        case UNBLOCK_VOUCHER:
                            item.voucherStatusId = VOUCHER_STATUS.SOLD.getStatusId()
                            filteredList.add(item)
                            break
                        case VOUCHER_REDEEM:
                            item.voucherStatusId = VOUCHER_STATUS.REDEEMED.getStatusId()
                            filteredList.add(item)
                            break
                        case BLOCK_VOUCHER:
                            item.voucherStatusId = VOUCHER_STATUS.BLOCKED.getStatusId()
                            filteredList.add(item);
                            break
                    }
                }
            }

        }
        log.debug("Prepared aggregation. Prepared list : ${filteredList}")
        return filteredList;
    }

    private def updateAggregation(List filteredList) {


        if (filteredList) {

            // upsert  data
            def sql_insert = "insert into ${TABLE} (serial_no, voucher_code, price_value, price_currency, expiry_date, subscriber_msisdn, reseller_msisdn, ers_reference, client_reference, vouchers_status_id, profile_id) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
            def sql_update = "UPDATE subscriber_msisdn= IFNULL(?, subscriber_msisdn), reseller_msisdn= IFNULL(?, reseller_msisdn), ers_reference= ?, client_reference = IFNULL(?, client_reference), vouchers_status_id= ?, profile_id= ?"

            def sql = sql_insert + " ON DUPLICATE KEY " + sql_update

            log.debug("Using sql query for Insert/Update - " + sql)


            int index = 0;
            jdbcTemplate.batchUpdate(sql, [
                    setValues   : { ps, i ->
                        index = 0;
                        ps.setString(++index, filteredList[i].serialNo)
                        ps.setString(++index, filteredList[i].voucherCode ?: 0l)
                        ps.setDouble(++index, filteredList[i].priceValue ?: 0l)
                        ps.setString(++index, filteredList[i].priceCurrency)
                        ps.setDate(++index, (filteredList[i].expiryDate ? toSqlDate(filteredList[i].expiryDate) : null))
                        ps.setString(++index, filteredList[i].subscriberMSISDN)
                        ps.setString(++index, filteredList[i].resellerMSISDN)
                        ps.setString(++index, filteredList[i].ersReference)
                        ps.setString(++index, filteredList[i].clientReference)
                        ps.setInt(++index, filteredList[i].voucherStatusId)
                        ps.setString(++index, filteredList[i].profileId)

                        //update
                        ps.setString(++index, filteredList[i].subscriberMSISDN)
                        ps.setString(++index, filteredList[i].resellerMSISDN)
                        ps.setString(++index, filteredList[i].ersReference)
                        ps.setString(++index, filteredList[i].clientReference)
                        ps.setInt(++index, filteredList[i].voucherStatusId)
                        ps.setString(++index, filteredList[i].profileId)


                    },
                    getBatchSize: { filteredList.size() }
            ] as BatchPreparedStatementSetter)
        }
    }

    private def parseDate(String dateStr) {
        try {
            if (dateStr.length() == voucherExpiryFormat.length()) {
                return Date.parse(voucherExpiryFormat, dateStr)
            } else {
                return Date.parse(voucherExpirySecondaryFormat, dateStr)
            }
        }
        catch (Exception e) {
            log.warn("Unknown Date Format in date : ${dateStr}")
            throw e;
        }
    }
}
