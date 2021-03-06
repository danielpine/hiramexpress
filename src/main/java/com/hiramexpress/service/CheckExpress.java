package com.hiramexpress.service;

import com.alibaba.druid.util.StringUtils;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.hiramexpress.domain.CheckRecord;
import com.hiramexpress.domain.Result;
import com.hiramexpress.domain.enums.PlatformEnum;
import com.hiramexpress.domain.enums.ResultEnum;
import com.hiramexpress.service.checkimpl.KDNIAOService;
import com.hiramexpress.service.checkimpl.KDPTService;
import com.hiramexpress.service.checkimpl.KdniaoTrackQueryAPI;
import com.hiramexpress.utils.AnalysisUtil;
import com.hiramexpress.utils.ClientIPUtils;
import com.hiramexpress.utils.ResultUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import javax.servlet.http.HttpServletRequest;
import java.text.SimpleDateFormat;
import java.util.*;

@Service
public class CheckExpress {

    private final Logger logger = LoggerFactory.getLogger(CheckExpress.class);

    private final RedisService redisService;      // 伙伴数据资源
    private final KDNIAOService kdniaoService;  // 快递鸟
    private final KDPTService kdptService;      //
    private final ConvertExpress convertExpress;      // 伙伴数据资源
    private final KdniaoTrackQueryAPI api;
    private final AnalysisExpress analysisExpress;
    private final RecordService recordService;
    private final CheckRecordService checkRecordService;
    private final RateService rateService;
    private final AnalysisUtil analysisUtil;

    @Autowired
    public CheckExpress(RedisService redisService, KDNIAOService kdniaoService, KDPTService kdptService, ConvertExpress convertExpress, KdniaoTrackQueryAPI api, AnalysisExpress analysisExpress, RecordService recordService, RateService rateService, AnalysisUtil analysisUtil, CheckRecordService checkRecordService) {
        this.redisService = redisService;
        this.kdniaoService = kdniaoService;
        this.kdptService = kdptService;
        this.convertExpress = convertExpress;
        this.api = api;
        this.analysisExpress = analysisExpress;
        this.recordService = recordService;
        this.checkRecordService = checkRecordService;
        this.rateService = rateService;
        this.analysisUtil = analysisUtil;
    }

    public Result checkExpress(String shipperCode, String logisticCode, boolean useAnalysis, String analysisPlatform) throws Exception {
        logger.info("--->>> ShipperCode: " + shipperCode + " & LogisticCode: " + logisticCode + " & useAnalysis: " + useAnalysis);
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        HttpServletRequest request = attributes.getRequest();
        String realIp = ClientIPUtils.getClientIp(request);

        String redisKey = "checkNum_" + new SimpleDateFormat("yyyyMMdd").format(new Date());    // eg: checkNum_20181107
        int checkNum = Integer.parseInt(StringUtils.isEmpty(redisService.get(redisKey)) ? "0" : redisService.get(redisKey));
        logger.info("--->>> redis data: key:" + redisKey + " value:" + checkNum);
        String newCount = checkNum + 1 + "";
        redisService.set(redisKey, newCount, 60 * 60 * 24L);

        String newShipperCode = shipperCode;
        CheckRecord checkRecord = new CheckRecord();
        checkRecord.setCheckLogisticCode(logisticCode);
        checkRecord.setCheckDate(new Date());
        checkRecord.setCheckRealIp(realIp);
        checkRecord.setCheckReason("");
        if (useAnalysis) {
            newShipperCode = analysisUtil.analysis(shipperCode.toUpperCase(), analysisPlatform);
            logger.info("--->>> analysis: " + shipperCode + " to " + newShipperCode);
            if (StringUtils.isEmpty(newShipperCode)) {
                checkRecord.setCheckResult(-1);
                checkRecord.setCheckReason(ResultEnum.NO_EXPRESS.getMsg());
                checkRecordService.addCheckRecord(checkRecord);
                return ResultUtil.error(ResultEnum.NO_EXPRESS, newCount);
            }
        }
        checkRecord.setCheckShipperCode(newShipperCode);

        Map<String, IExpressService> servicesMap = new LinkedHashMap<>();
        servicesMap.put(PlatformEnum.KDNIAO.name(), kdniaoService);
        servicesMap.put(PlatformEnum.KDPT.name(), kdptService);

        String finalShipperCode;
        Iterator<String> keysIterator = servicesMap.keySet().iterator();
        JSONObject checkResult = new JSONObject();

        while (keysIterator.hasNext()) {
            String platform = keysIterator.next();
            finalShipperCode = convertExpress.convert(newShipperCode, platform);
            if (StringUtils.isEmpty(finalShipperCode)) {
                checkResult.put("success", false);
                checkResult.put("reason", ResultEnum.NO_EXPRESS);
                continue;
            }
            checkResult = servicesMap.get(platform).checkExpress(finalShipperCode, logisticCode);
            if (checkResult == null) {
                continue;
            } else if (checkResult.getBoolean("success") != null && checkResult.getBoolean("success")) {
                break;
            } else {
                logger.info("--->>> " + platform + "  got a false with " + checkResult.getString("reason") + ". Try other platform...");
                checkResult = null;
                continue;
            }
        }
        if (checkResult == null) {
            logger.info("--->>> No data.");
            checkRecord.setCheckResult(-1);
            checkRecord.setCheckReason(ResultEnum.NO_DATA.getMsg());
            checkRecordService.addCheckRecord(checkRecord);
            return ResultUtil.error(ResultEnum.NO_DATA, newCount);
        }
        if (!checkResult.getBoolean("success")) {
            checkRecord.setCheckResult(-1);
            checkRecord.setCheckReason(ResultEnum.valueOf(checkResult.getString("reason")).getMsg());
            checkRecordService.addCheckRecord(checkRecord);
            return ResultUtil.error(ResultEnum.valueOf(checkResult.getString("reason")), newCount);
        }
        checkRecord.setCheckPlatform(checkResult.getString("platform"));
        checkRecordService.addCheckRecord(checkRecord);
        return ResultUtil.success(newCount, checkResult);
    }

    public Result<?> getCount() {
        String redisKey = "checkNum_" + new SimpleDateFormat("yyyyMMdd").format(new Date());    // eg: checkNum_20181107
        int checkNum = Integer.parseInt(StringUtils.isEmpty(redisService.get(redisKey)) ? "0" : redisService.get(redisKey));
        JSONObject count = new JSONObject();
        count.put("todayCount", checkNum);
        count.put("historyCount", recordService.allTimes());
        return ResultUtil.success(count);
    }

    public Result<?> getExpressList() {
        JSONObject obj = convertExpress.listExpress();
        if (obj != null) {
            return ResultUtil.success(obj.keySet());
        }
        return ResultUtil.error(ResultEnum.NO_DATA);
    }

    public Result<?> rate(String message, String email, int stars) {
        if (rateService.saveRate(message, email, stars) > 0) {
            return ResultUtil.success();
        }
        return ResultUtil.error(ResultEnum.ERROR);
    }

    public Result<?> statistics() {
        JSONObject record = recordService.findRecords();
        JSONArray checkRecords = checkRecordService.findCheckRecords();
        JSONObject result = new JSONObject();
        result.put("record", record);
        result.put("checkRecord", checkRecords);
        return ResultUtil.success(result);
    }
}
