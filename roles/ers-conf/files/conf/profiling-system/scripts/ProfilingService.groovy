package se.seamless.mcs.profilingsystem.services.impl

import etm.core.configuration.EtmManager
import etm.core.monitor.EtmPoint
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import org.springframework.stereotype.Service
import se.seamless.mcs.profilingsystem.api.model.request.CustomerProfileRequestInfo
import se.seamless.mcs.profilingsystem.services.impl.AbstractProfilingService

import java.util.concurrent.ConcurrentHashMap

import static se.seamless.mcs.profilingsystem.util.ProfilingSystemConstants.*

/**
 * Credit Scores and Amount (for which loan will be given) is calculated here.
 */
@Service("ProfilingService")
public class ProfilingService extends AbstractProfilingService {

    private Logger logger = LogManager.getLogger(getClass());


    @Override
    public Map<String, Object> getProfileInfo(CustomerProfileRequestInfo requestInfo) {
        Map<String, Object> profileMap;
        Map<String, Object> dynamicParams;
        EtmPoint point = null;
        try {
            point = EtmManager.getEtmMonitor().createPoint("ProfilingService::getProfileInfo");
            dynamicParams = new ConcurrentHashMap<>();

            getDateRange(dynamicParams);
            dynamicParams.put(CUSTOMER_MSISDN_KEY, requestInfo.getMsisdn());

            logger.info("Going to call sales data service with principal : " + requestInfo + " and dynamicParams " + dynamicParams);

            profileMap = salesDataService.findCreditScoreMetrics(dynamicParams);
            return profileMap;
        }
        catch (Exception e)
        {
            logger.error("An exception has occurred in getProfileInfo Groovy. ", e);
            throw e;
        }
        finally {
            point?.collect();
        }
    }

    @Override
    public boolean checkEligibility(Map<String, Object> profileMap) {
        Double systemCreditScore;
        Double resellerCreditScore;

        systemCreditScore = (Double)profileMap.get(SYSTEM_CREDIT_SCORE_KEY);
        resellerCreditScore = (Double) profileMap.get(RESELLER_CREDIT_SCORE_KEY);
        if(resellerCreditScore >= microCreditConfiguration.getEligibilityCreditScorePercentage() * systemCreditScore / 100.0d &&
            resellerCreditScore > 0)
        {
            return true;
        }

        return false;
    }

    @Override
    public void calculateCreditScore(Map<String, Object> profileMap) {

        creditScoringService.calculateCreditScore(profileMap);
    }

    /**
     * Gets the date range for which profile needs to be calculated. Also moves the cycle window.
     * @param dynamicParams
     */
    protected void getDateRange(Map<String, Object> dynamicParams) {
        super.getDateRange(dynamicParams);
    }
}