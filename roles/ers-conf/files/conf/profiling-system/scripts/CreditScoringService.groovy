package se.seamless.mcs.profilingsystem.services.impl

import org.springframework.stereotype.Service
import se.seamless.mcs.profilingsystem.services.impl.AbstractCreditScoringService

import static se.seamless.mcs.profilingsystem.util.ProfilingSystemConstants.*

/**
 * Credit score calculation and corresponding amount calculation can be manipulated here.
 * Micro credit amount will be calculated on score and amount calculated here.
 * Created by danish.amjad on 4/10/17.
 */

@Service("CreditScoringService")
public class CreditScoringService extends AbstractCreditScoringService {

    @Override
    void calculateResellerCreditScore(Map<String, Object> profileMap) {

        if (!isValidData(profileMap)) {
            return;
        }

        Double resellerCreditScore;
        resellerCreditScore = (Double) profileMap.get(RESELLER_SALE_SUM_KEY);
        profileMap.put(RESELLER_CREDIT_SCORE_KEY, resellerCreditScore);
    }

    @Override
    void calculateResellerCreditScoreInAmount(Map<String, Object> profileMap) {

        if (!isValidData(profileMap)) {
            return;
        }
        profileMap.put(RESELLER_CREDIT_SCORE_IN_AMOUNT_KEY, profileMap.get(RESELLER_CREDIT_SCORE_KEY));

    }

    @Override
    void calculateSystemCreditScore(Map<String, Object> profileMap) {

        if (!isValidData(profileMap)) {
            return;
        }

        Double systemAverageSale;
        Double uniqueResellerCount;
        Double systemTopupCount;
        Double systemCreditScoreAmount;

        systemTopupCount = (Double) profileMap.get(SYSTEM_TRANSACTION_COUNT_KEY);
        uniqueResellerCount = (Double) profileMap.get(UNIQUE_RESELLERS_COUNT_KEY);
        systemAverageSale = (Double) profileMap.get(SYSTEM_AVERAGE_SALE_KEY);

        systemCreditScoreAmount = systemAverageSale * systemTopupCount / uniqueResellerCount;

        profileMap.put(SYSTEM_CREDIT_SCORE_KEY, systemCreditScoreAmount);
    }

    @Override
    void calculateSystemCreditScoreInAmount(Map<String, Object> profileMap) {

        if (!isValidData(profileMap)) {
            return;
        }
        profileMap.put(SYSTEM_CREDIT_SCORE_IN_AMOUNT_KEY, profileMap.get(SYSTEM_CREDIT_SCORE_KEY));

    }


    @Override
    void doPreProcessing(Map<String, Object> profileMap) {

        if (!isValidData(profileMap)) {
            profileMap.put(SYSTEM_CREDIT_SCORE_KEY, -1.0d);
            profileMap.put(RESELLER_CREDIT_SCORE_KEY, -1.0d);
            profileMap.put(CREDIT_SCORE_KEY, -1.0d);
            profileMap.put(CREDIT_SCORE_IN_AMOUNT_KEY, -1.0d);
            profileMap.put(SYSTEM_CREDIT_SCORE_IN_AMOUNT_KEY, -1.0d);
            profileMap.put(RESELLER_CREDIT_SCORE_IN_AMOUNT_KEY, -1.0d);
        }


    }

    static boolean isValidData(Map<String, Object> profileMap){
        Double systemTransactionCount;

        systemTransactionCount = (Double) profileMap.get(SYSTEM_TRANSACTION_COUNT_KEY);
        return !(null == systemTransactionCount || systemTransactionCount.equals(0.0d));
    }

    @Override
    protected Double performCustomScoringTypeOnScore(Map<String, Object> profileMap) {
        Double resellerScore;
        Double systemScore;

        resellerScore = (Double)profileMap.get(RESELLER_CREDIT_SCORE_KEY);
        systemScore = (Double)profileMap.get(SYSTEM_CREDIT_SCORE_KEY);

        return 0.7 * resellerScore + 0.3 * systemScore;
    }

    @Override
    protected Double performCustomScoringTypeOnAmount(Map<String, Object> profileMap) {
        Double resellerAmount;
        Double systemAmount;

        resellerAmount = (Double)profileMap.get(RESELLER_CREDIT_SCORE_IN_AMOUNT_KEY);
        systemAmount = (Double)profileMap.get(SYSTEM_CREDIT_SCORE_IN_AMOUNT_KEY);

        return 0.7 * resellerAmount + 0.3 * systemAmount;
    }
}
