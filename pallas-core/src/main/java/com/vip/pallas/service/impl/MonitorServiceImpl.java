package com.vip.pallas.service.impl;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.google.common.base.Function;
import com.google.common.collect.Lists;
import com.vip.pallas.bean.monitor.*;
import com.vip.pallas.bean.monitor.MonitorMetricModel.MetricModel;
import com.vip.pallas.exception.PallasException;
import com.vip.pallas.mybatis.entity.Cluster;
import com.vip.pallas.service.ClusterService;
import com.vip.pallas.service.ElasticSearchService;
import com.vip.pallas.service.MonitorService;
import com.vip.pallas.utils.ConstantUtil;
import com.vip.pallas.utils.MetricConvertUtil;
import freemarker.template.Template;
import freemarker.template.TemplateException;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.ui.freemarker.FreeMarkerConfigurationFactoryBean;

import java.io.IOException;
import java.io.StringWriter;
import java.util.*;

@Service
public class MonitorServiceImpl implements MonitorService {

    private static final Logger logger = LoggerFactory.getLogger(MonitorServiceImpl.class);

    @Autowired
    private ElasticSearchService elasticSearchService;

    @Autowired
    private ClusterService clusterService;

    @Autowired
    private FreeMarkerConfigurationFactoryBean freeMarkerBean;

    private static Map<String,Integer> intervalMap = new HashMap<>();
    static {
        intervalMap.put("30s", 30);
        intervalMap.put("1m", 60);
        intervalMap.put("2m", 120);
        intervalMap.put("5m", 300);
        intervalMap.put("10m", 600);
        intervalMap.put("30m",1800);
        intervalMap.put("1h", 3600);
    }
    /**
     * 查询目标所在的类路径目录
     */
    private static final String TEMPALTE_FILE_PATH = "/templates";

    /**
     * 获取填充模板的map
     * @param queryModel
     * @return
     */
    private Map<String, Object> getDataMap(MonitorQueryModel queryModel) {
        Map<String, Object> dataMap = new HashMap<>();
        dataMap.put("beginTime", queryModel.getFrom());
        dataMap.put("endTime", queryModel.getTo());
        dataMap.put("cluserName", queryModel.getClusterName());
        dataMap.put("interval_unit", getIntevalString(queryModel.getFrom(), queryModel.getTo()));

        return dataMap;
    }

    @Override
    public ClusterMetricInfoModel queryClusterMetrics(MonitorQueryModel queryModel) throws Exception{

        Map<String, Object> dataMap = getDataMap(queryModel);

        Cluster cluster =  getCluster(queryModel.getClusterName());
        ClusterMetricInfoModel clusterMetricInfoModel = new ClusterMetricInfoModel();

        clusterMetricInfoModel.setGaugeMetric(queryClusterInfo(queryModel));

        //derivative aggs
        dataMap.put("type", ConstantUtil.TYPE_INDICES_STATS);
        dataMap.put("isDerivative", true);

        Template templateAggs = getTempalte(ConstantUtil.AGGS_STATS_TEMPLATE);

        List<MetricModel<Date, Long>> searchRate = getClusterSearchRate(templateAggs, dataMap, "indices_stats.indices_all.total.search.query_total", cluster);
        List<MetricModel<Date, Long>> indexingRate = getClusterIndexingRate(templateAggs, dataMap, "indices_stats.indices_all.total.indexing.index_total", cluster);
        List<MetricModel<Date, Long>> searchTime = getClusterSearchTime(templateAggs, dataMap, "indices_stats.indices_all.total.indexing.query_time_in_millis", cluster);
        List<MetricModel<Date, Long>> indexingTime = getClusterIndexingTime(templateAggs, dataMap, "indices_stats.indices_all.total.indexing.index_time_in_millis", cluster);
        setsearchLatency(searchRate, searchTime, clusterMetricInfoModel, dataMap);
        setIndexingLatency(indexingRate, indexingTime, clusterMetricInfoModel, dataMap);

        return clusterMetricInfoModel;
    }

    @Override
    public NodeMetricInfoModel queryNodeMetrics(MonitorQueryModel queryModel) throws Exception{

        Map<String, Object> dataMap = getDataMap(queryModel);

        dataMap.put("type", ConstantUtil.TYPE_NODE_STATS);
        dataMap.put("nodeName", queryModel.getNodeName());

        Cluster cluster =  getCluster(queryModel.getClusterName());
        NodeMetricInfoModel nodeMetricInfoModel = new NodeMetricInfoModel();

        nodeMetricInfoModel.setGaugeMetric(queryNodesInfo(queryModel).get(0));

        //normal aggs
        Template templateAggs = getTempalte(ConstantUtil.AGGS_STATS_TEMPLATE);

        dataMap.put("isDerivative", false);

        nodeMetricInfoModel.setCpuNodePercent(getNodeCpuPercent(templateAggs, dataMap, "os.cpu.percent", cluster));
        nodeMetricInfoModel.setCpuProcessPerent(getNodeCpuPercent(templateAggs, dataMap, "process.cpu.percent", cluster));
        nodeMetricInfoModel.setGcCountOld(getNodeGcCount(templateAggs, dataMap, "jvm.gc.collectors.old.collection_count", cluster));
        nodeMetricInfoModel.setGcCountYoung(getNodeGcCount(templateAggs, dataMap, "jvm.gc.collectors.young.collection_count", cluster));
        nodeMetricInfoModel.setGc_duration_old_ms(getNodeGcDuration(templateAggs, dataMap, "jvm.gc.collectors.old.collection_time_in_millis", cluster));
        nodeMetricInfoModel.setGc_duration_young_ms(getNodeGcDuration(templateAggs, dataMap, "jvm.gc.collectors.young.collection_time_in_millis", cluster));
        nodeMetricInfoModel.setJvm_heap_max_byte(getNodeJvmHeap(templateAggs, dataMap, "jvm.mem.heap_max_in_bytes", cluster));
        nodeMetricInfoModel.setJvm_heap_used_byte(getNodeJvmHeap(templateAggs, dataMap, "jvm.mem.heap_used_in_bytes", cluster));
        nodeMetricInfoModel.setHttpOpenCurrent(getNodeHttpOpen(templateAggs, dataMap, "http.current_open", cluster));

        nodeMetricInfoModel.setIndex_memory_lucenc_total_byte(getNodeIndexMemory(templateAggs, dataMap, "indices.segments.memory_in_bytes", cluster));
        nodeMetricInfoModel.setIndex_memory_terms_bytes(getNodeIndexMemory(templateAggs, dataMap, "indices.segments.terms_memory_in_bytes", cluster));
        //nodeMetricInfoModel.setIndexingLatency();
        //nodeMetricInfoModel.setSearchLatency();
        nodeMetricInfoModel.setSegmentCount(getNodeSegmentCount(templateAggs, dataMap, "indices.segments.count", cluster));

        nodeMetricInfoModel.setSearchThreadpoolQueue(getNodeThreadpool(templateAggs, dataMap, "thread_pool.search.queue", cluster));
        nodeMetricInfoModel.setSearchThreadpoolReject(getNodeThreadpool(templateAggs, dataMap, "thread_pool.search.reject", cluster));
        nodeMetricInfoModel.setIndexThreadpoolQueue(getNodeThreadpool(templateAggs, dataMap, "thread_pool.index.queue", cluster));
        nodeMetricInfoModel.setIndexThreadpoolReject(getNodeThreadpool(templateAggs, dataMap, "thread_pool.index.reject", cluster));
        nodeMetricInfoModel.setBulkThreadpoolQueue(getNodeThreadpool(templateAggs, dataMap, "thread_pool.bulk.queue", cluster));
        nodeMetricInfoModel.setBulkThreadpoolReject(getNodeThreadpool(templateAggs, dataMap, "thread_pool.bulk.reject", cluster));

        //derivative aggs
        dataMap.put("isDerivative", true);

        return nodeMetricInfoModel;
    }

    @Override
    public IndexMetricInfoModel queryIndexMetrices(MonitorQueryModel queryModel) throws Exception{

        Map<String, Object> dataMap = getDataMap(queryModel);

        //guage
        dataMap.put("type", ConstantUtil.TYPE_INDEX_STATS);
        dataMap.put("indexName", queryModel.getIndexName());

        Cluster cluster =  getCluster(queryModel.getClusterName());

        IndexMetricInfoModel indexMetricInfoModel = new IndexMetricInfoModel();

        indexMetricInfoModel.setGaugeMetric(queryIndicesInfo(queryModel).get(0));

        Template templateAggs = getTempalte(ConstantUtil.AGGS_STATS_TEMPLATE);

        dataMap.put("isDerivative", false);

        indexMetricInfoModel.setIndex_memory_terms_in_byte(getIndex_memory(templateAggs, dataMap, "index_stats.total.segments.terms_memory_in_bytes", cluster));
        indexMetricInfoModel.setIndex_memory_lucenc_total_in_byte(getIndex_memory(templateAggs, dataMap, "index_stats.total.segments.memory_in_bytes", cluster));

        indexMetricInfoModel.setSegmentCount(getIndexSegmentCount(templateAggs, dataMap, "index_stats.total.segments.count", cluster));
        indexMetricInfoModel.setDocumentCount(getIndexDocumentCount(templateAggs, dataMap, "index_stats.total.docs.count", cluster));
        indexMetricInfoModel.setIndex_disk_primary(getIndex_disk(templateAggs, dataMap, "index_stats.primaries.store.size_in_bytes", cluster));
        indexMetricInfoModel.setIndex_disk_total(getIndex_disk(templateAggs, dataMap, "index_stats.total.store.size_in_bytes", cluster));

        dataMap.put("isDerivative", true);
      //  indexMetricInfoModel.setSearchRate(getIndexSearchRate(templateAggs, dataMap, "index_stats.total.search.query_total", cluster));
       // indexMetricInfoModel.setIndexingRate(getIndexIndexingRate(templateAggs, dataMap, "index_stats.total.indexing.index_total", cluster));

        return indexMetricInfoModel;
    }

    @Override
    public ClusterGaugeMetricModel queryClusterInfo(MonitorQueryModel queryModel) throws Exception {
        ClusterGaugeMetricModel gaugeMetricModel = new ClusterGaugeMetricModel();
        Cluster cluster =  getCluster(queryModel.getClusterName());

        JSONObject versionJsonObj = JSONObject.parseObject( elasticSearchService.runDsl(cluster.getHttpAddress(), "/"));
        JSONObject  healthJsonobj = JSONObject.parseObject(elasticSearchService.runDsl(cluster.getHttpAddress(), "/_cluster/health"));
        JSONObject statsJsonobj = JSONObject.parseObject(elasticSearchService.runDsl(cluster.getHttpAddress(), "/_cluster/stats"));

        gaugeMetricModel.setVersion(versionJsonObj.getJSONObject("version").getString("number"));
        gaugeMetricModel.setUnassignedShardCount(healthJsonobj.getLong("unassigned_shards"));
        gaugeMetricModel.setHealth(healthJsonobj.getString("status"));

        JSONObject indicesJsonObj = statsJsonobj.getJSONObject("indices");
        JSONObject nodesJsonObj = statsJsonobj.getJSONObject("nodes");

        gaugeMetricModel.setNodeCount(nodesJsonObj.getJSONObject("count").getInteger("total"));
        gaugeMetricModel.setIndexCount(indicesJsonObj.getLong("count"));
        gaugeMetricModel.setTotal_memory_byte(nodesJsonObj.getJSONObject("jvm").getJSONObject("mem").getLong("heap_max_in_bytes"));
        gaugeMetricModel.setUsed_memory_byte(nodesJsonObj.getJSONObject("jvm").getJSONObject("mem").getLong("heap_used_in_bytes"));
        gaugeMetricModel.setTotalShardCount(indicesJsonObj.getJSONObject("shards").getLong("total"));
        gaugeMetricModel.setDocument_store_byte(indicesJsonObj.getJSONObject("store").getLong("size_in_bytes"));
        gaugeMetricModel.setDocumentCount(indicesJsonObj.getJSONObject("docs").getLong("count"));
        gaugeMetricModel.setMax_uptime_in_millis(nodesJsonObj.getJSONObject("jvm").getLong("max_uptime_in_millis"));


        return gaugeMetricModel;
    }

    @Override
    public List<NodeGaugeMetricModel> queryNodesInfo(MonitorQueryModel queryModel) throws Exception {
        Map<String, NodeGaugeMetricModel>  haha = new HashMap<>();

        List<NodeGaugeMetricModel> result = new ArrayList<>();
        Cluster cluster =  getCluster(queryModel.getClusterName());
        //find all node by clusterName    /_cat/nodes
        List<String[]> nodeInfos = elasticSearchService.getNodesInfos(queryModel.getClusterName());
        if(null == nodeInfos || nodeInfos.size() == 0){
            return new ArrayList<>();
        }
        //gauge metric group by nodeName
        Map<String/*nodeName*/, ShardInfoModel> shardInfoModelMap = elasticSearchService.getShardsNode(queryModel.getClusterName());
        String  metricsJsonString = elasticSearchService.runDsl(cluster.getHttpAddress(), "/_nodes/stats/fs,jvm,indices,process");
        JSONObject rawJsonObj = JSONObject.parseObject(metricsJsonString).getJSONObject("nodes");
        JSONObject metricsJsonObj = new JSONObject();
        rawJsonObj.forEach((k, v) -> {
            metricsJsonObj.put(rawJsonObj.getJSONObject(k).getString("name"), rawJsonObj.getJSONObject(k));
        });

        for(String[] nodeInfo: nodeInfos) {
            String nodeName = nodeInfo[9];
            if(StringUtils.isNotEmpty(queryModel.getNodeName()) && !queryModel.getNodeName().equals(nodeName)) {
                continue;
            }

            NodeGaugeMetricModel gaugeMetricModel = new NodeGaugeMetricModel();
            gaugeMetricModel.setNodeName(nodeName);
            gaugeMetricModel.setOsCpuPercent(Double.valueOf(nodeInfo[3]));
            gaugeMetricModel.setLoad_1m(Double.valueOf(nodeInfo[4]));
            gaugeMetricModel.setNodeRole(nodeInfo[7]);
            gaugeMetricModel.setMaster("*".equals(nodeInfo[8]));

            gaugeMetricModel.setTransportAddress(metricsJsonObj.getJSONObject(nodeName).getString("ip"));
            gaugeMetricModel.setProcessCpuPercent(metricsJsonObj.getJSONObject(nodeName).getJSONObject("process").getJSONObject("cpu").getDouble("percent"));
            gaugeMetricModel.setUptime_in_ms(metricsJsonObj.getJSONObject(nodeName).getJSONObject("jvm").getLong("uptime_in_millis"));
            gaugeMetricModel.setJvmHeapUsage(metricsJsonObj.getJSONObject(nodeName).getJSONObject("jvm").getJSONObject("mem").getDouble("heap_used_percent"));
            gaugeMetricModel.setAvailableFS(metricsJsonObj.getJSONObject(nodeName).getJSONObject("fs").getJSONObject("total").getLong("available_in_bytes"));
            gaugeMetricModel.setDocumentCount(metricsJsonObj.getJSONObject(nodeName).getJSONObject("indices").getJSONObject("docs").getLong("count"));
            gaugeMetricModel.setDocumentStore(metricsJsonObj.getJSONObject(nodeName).getJSONObject("indices").getJSONObject("store").getLong("size_in_bytes"));
            gaugeMetricModel.setIndexCount(shardInfoModelMap.get(nodeName).getIndexCount());
            gaugeMetricModel.setShardCount(shardInfoModelMap.get(nodeName).getTotalShards());
            gaugeMetricModel.setNodeName(nodeName);

            result.add(gaugeMetricModel);

        }

        return result;
    }

    @Override
    public List<IndexGaugeMetricModel> queryIndicesInfo(MonitorQueryModel queryModel) throws Exception {

        List<IndexGaugeMetricModel> result = new ArrayList<>();
        Cluster cluster =  getCluster(queryModel.getClusterName());
        //find all index by clusterName    /_cat/indices
        List<String[]> indexInfos = elasticSearchService.getIndexInfos(queryModel.getClusterName());
        if(null == indexInfos || indexInfos.size() == 0) {
            return result;
        }

        //gauge metric group by indexName
        Map<String/*IndexName*/, ShardInfoModel>  shardInfoModelMap = elasticSearchService.getShardsIndex(queryModel.getClusterName());
        for(String[] indexInfo : indexInfos) {
            String indexName = indexInfo[2];
            if(StringUtils.isNotEmpty(queryModel.getIndexName()) && !queryModel.getIndexName().equals(indexName)){
                continue;
            }
            IndexGaugeMetricModel gaugeMetricModel = new IndexGaugeMetricModel();
            gaugeMetricModel.setHealth(indexInfo[0]);
            gaugeMetricModel.setStatus(indexInfo[1]);
            gaugeMetricModel.setDocumentCount(Long.valueOf(indexInfo[6]));
            gaugeMetricModel.setDocument_store_byte_total(indexInfo[8]);
            gaugeMetricModel.setDocument_store_byte_primary(indexInfo[9]);
            gaugeMetricModel.setPrimaryShardCount(Integer.valueOf(indexInfo[4]));
            gaugeMetricModel.setReplicaShardCount(Integer.valueOf(indexInfo[5]));
            gaugeMetricModel.setTotalShardCount(gaugeMetricModel.getPrimaryShardCount() * (1 + gaugeMetricModel.getReplicaShardCount()));
            gaugeMetricModel.setUnassignedShardCount(shardInfoModelMap.get(indexName) == null ? 0: shardInfoModelMap.get(indexName).getUnassignedShards());
            gaugeMetricModel.setIndexName(indexName);
            result.add(gaugeMetricModel);

        }

        return result;
    }

    @Override
    public Integer getNodeCount(String clusterName) throws Exception {
        getCluster(clusterName);
        List<String[]> nodeInfos = elasticSearchService.getNodesInfos(clusterName);
        if(null == nodeInfos || nodeInfos.size() == 0){
            return 0;
        }
        return nodeInfos.size();
    }

    @Override
    public Integer getIndexCount(String clusterName) throws Exception {
        getCluster(clusterName);
        List<String[]> indexInfos = elasticSearchService.getIndexInfos(clusterName);
        if(null == indexInfos || indexInfos.size() == 0) {
            return 0;
        }
        return indexInfos.size();
    }

    private String getEndPoint(Map<String, Object> dataMap) {
        String indexName= ConstantUtil.indexName;
        StringBuilder result = new StringBuilder();
        result.append("/").append(indexName).append("/_search");
        return result.toString();
    }

//    private Configuration initTemplateConfiguration() {
//        Configuration cfg = new Configuration(Configuration.VERSION_2_3_23);
//        cfg.setClassForTemplateLoading(this.getClass(), TEMPALTE_FILE_PATH);
//        cfg.setDefaultEncoding("UTF-8");
//        cfg.setNumberFormat("#");
//        cfg.setTemplateExceptionHandler(TemplateExceptionHandler.RETHROW_HANDLER);
//        return cfg;
//    }

    public static String getIndexName(String indexName, String dateString) {
        StringBuilder result = new StringBuilder();
        result.append(indexName).append(".").append(dateString.replaceAll("-", "\\."));
        return result.toString();
    }

    /**
     *
     * gauge metric
     *
     */

    private ClusterGaugeMetricModel getClusterHealthGauge(Template template, Map<String, Object> dataMap, Cluster cluster) throws PallasException {
        ClusterGaugeMetricModel clusterGaugeMetricModel = new ClusterGaugeMetricModel();

        String result = getMetricFromES(template, dataMap, "", cluster);


        JSONArray jsonArray = JSON.parseObject(result).getJSONObject("hits").getJSONArray("hits");
        if(null == jsonArray || jsonArray.size() == 0) {
            return new ClusterGaugeMetricModel();
        }
        JSONObject clusterHealthJsonObj = jsonArray.getJSONObject(0).getJSONObject("_source").getJSONObject("cluster_health");
        clusterGaugeMetricModel.setUnassignedShardCount(clusterHealthJsonObj.getLong("unassigned_shards"));
        clusterGaugeMetricModel.setHealth(clusterHealthJsonObj.getString("status"));

       return clusterGaugeMetricModel;
    }

    private ClusterGaugeMetricModel getClusterStatsGauge(ClusterGaugeMetricModel gaugeMetricModel, Template template, Map<String, Object> dataMap, Cluster cluster) throws PallasException {

        if(null == gaugeMetricModel) {
            gaugeMetricModel = new ClusterGaugeMetricModel();
        }
        String result = getMetricFromES(template, dataMap, "", cluster);

        JSONArray jsonArray = JSON.parseObject(result).getJSONObject("hits").getJSONArray("hits");
        if(null == jsonArray || jsonArray.size() == 0) {
            return new ClusterGaugeMetricModel();
        }

        JSONObject clusterStatsJsonObj = jsonArray.getJSONObject(0).getJSONObject("_source").getJSONObject("cluster_stats");

        JSONObject indicesJsonObj = clusterStatsJsonObj.getJSONObject("indices");
        JSONObject nodesJsonObj = clusterStatsJsonObj.getJSONObject("nodes");

        gaugeMetricModel.setNodeCount(nodesJsonObj.getJSONObject("count").getInteger("total"));
        gaugeMetricModel.setIndexCount(indicesJsonObj.getLong("count"));
        gaugeMetricModel.setTotal_memory_byte(nodesJsonObj.getJSONObject("jvm").getJSONObject("mem").getLong("heap_max_in_bytes"));
        gaugeMetricModel.setUsed_memory_byte(nodesJsonObj.getJSONObject("jvm").getJSONObject("mem").getLong("heap_used_in_bytes"));
        gaugeMetricModel.setTotalShardCount(indicesJsonObj.getJSONObject("shards").getLong("total"));
        gaugeMetricModel.setDocument_store_byte(indicesJsonObj.getJSONObject("store").getLong("size_in_bytes"));
        gaugeMetricModel.setDocumentCount(indicesJsonObj.getJSONObject("docs").getLong("count"));
        gaugeMetricModel.setMax_uptime_in_millis(nodesJsonObj.getJSONObject("jvm").getLong("max_uptime_in_millis"));
        //gaugeMetricModel.setVersion("");
        return gaugeMetricModel;
    }

    /**
     *
     * cluster metric aggs
     *
     */

    private List<MetricModel<Date, Long>> getClusterSearchRate(Template template, Map<String, Object> dataMap,  String fieldName, Cluster cluster) throws PallasException {
        List<MetricModel<Date, Double>> result = getMonitorMetricModels(template, dataMap,fieldName, cluster);
        return mapLong(result);

    }

    private List<MetricModel<Date, Long>> getClusterIndexingRate(Template template, Map<String, Object> dataMap,  String fieldName, Cluster cluster) throws PallasException {
        List<MetricModel<Date, Double>> result = getMonitorMetricModels(template, dataMap,fieldName, cluster);
        return mapLong(result);
    }

    private List<MetricModel<Date, Long>> getClusterSearchTime(Template template, Map<String, Object> dataMap,  String fieldName, Cluster cluster) throws PallasException {
        List<MetricModel<Date, Double>> result = getMonitorMetricModels(template, dataMap,fieldName, cluster);
        return mapLong(result);
    }

    private List<MetricModel<Date, Long>> getClusterIndexingTime(Template template, Map<String, Object> dataMap,  String fieldName, Cluster cluster) throws PallasException {
        List<MetricModel<Date, Double>> result = getMonitorMetricModels(template, dataMap,fieldName, cluster);
        return mapLong(result);
    }

    private void setIndexingLatency(List<MetricModel<Date, Long>> indexingRate, List<MetricModel<Date, Long>> indexingTime, ClusterMetricInfoModel clusterMetricInfoModel, Map<String, Object> dataMap) {

        if(indexingRate!= null && indexingRate.size() > 0 && indexingTime != null && indexingTime.size() > 0) {
            if(indexingRate.size() != indexingTime.size()) {
                logger.error("latency error: size not equals; indexingRate size: {}, indexingTime size: {}", indexingRate.size(), indexingTime.size());
            } else {
                List<MetricModel<Date, Double>> result = getResult(indexingRate, indexingTime);
                MonitorMetricModel<Date, Double> monitorMetricModel = new MonitorMetricModel<>(result, "ms");
                clusterMetricInfoModel.setIndexingLatency(monitorMetricModel);
            }
            clusterMetricInfoModel.setIndexingRate(calculatePerSec(indexingRate, (String)dataMap.get("interval_unit")));
        }

    }

    private void setsearchLatency(List<MetricModel<Date, Long>> searchRate, List<MetricModel<Date, Long>> searchTime, ClusterMetricInfoModel clusterMetricInfoModel, Map<String, Object> dataMap) {

        if(searchRate!= null && searchRate.size() > 0 && searchTime != null && searchTime.size() > 0) {
            if(searchRate.size() != searchTime.size()) {
                logger.error("latency error: size not equals; searchRate size: {}, searchTime size: {}", searchRate.size(), searchTime.size());
            } else{
                List<MetricModel<Date, Double>> result = (getResult(searchRate, searchTime));
                MonitorMetricModel<Date, Double> monitorMetricModel = new MonitorMetricModel<>();
                monitorMetricModel.setUnit("/ms");
                monitorMetricModel.setMetricModel(result);
                clusterMetricInfoModel.setSearchLatency(monitorMetricModel);
            }

            clusterMetricInfoModel.setSearchRate(calculatePerSec(searchRate,(String)dataMap.get("interval_unit")));
        }


    }

    private MonitorMetricModel<Date, Double> calculatePerSec(List<MetricModel<Date, Long>> models, String intervalString) {
        List<MetricModel<Date, Double>> result = new ArrayList<>();
        models.forEach(value -> {
            MetricModel<Date, Double> model = new MetricModel<>();
            model.setX(value.getX());
            model.setY(value.getY() * 1.0 / intervalMap.get(intervalString));
            result.add(model);
        });
        MonitorMetricModel<Date, Double> monitorMetricModel = new MonitorMetricModel<>(result, "/s");

        return monitorMetricModel;
    }

    private List<MetricModel<Date, Double>> getResult(List<MetricModel<Date, Long>> a, List<MetricModel<Date, Long>> b) {
        List<MetricModel<Date, Double>> result = new ArrayList<>();
        for(int i=0; i<a.size(); i++) {
            MetricModel<Date, Double> metricModel = new MetricModel<Date, Double>();
            metricModel.setX(a.get(i).getX());
            try{
                if(a.get(i).getY() == 0) {
                    metricModel.setY(0.0);
                } else {
                    metricModel.setY(b.get(i).getY() *1.0 / a.get(i).getY());
                }

            } catch (Exception e) {
                logger.error("divide error", e);
                metricModel.setY(0.0);
            }
            result.add(metricModel);
        }
        return result;
    }

    /**
     *
     * node metric aggs
     *
     */

    private  MonitorMetricModel<Date, Double> getNodeCpuPercent(Template template, Map<String, Object> dataMap, String fieldName, Cluster cluster) throws PallasException{
        List<MetricModel<Date, Double>> result = getMonitorMetricModels(template, dataMap, fieldName, cluster);
        MonitorMetricModel<Date, Double> monitorMetricModel = new MonitorMetricModel<>(result, "%");
        return monitorMetricModel;
    }

    private MonitorMetricModel<Date, Long> getNodeGcCount(Template template, Map<String, Object> dataMap,  String fieldName, Cluster cluster)throws PallasException {
        List<MetricModel<Date, Double>> result = getMonitorMetricModels(template, dataMap,fieldName, cluster);
        MonitorMetricModel<Date, Long> monitorMetricModel = new MonitorMetricModel<>(mapLong(result), "");
        return monitorMetricModel;
    }

    private MonitorMetricModel<Date, Long> getNodeGcDuration(Template template, Map<String, Object> dataMap,  String fieldName, Cluster cluster)throws PallasException {
        List<MetricModel<Date, Double>> result = getMonitorMetricModels(template, dataMap,fieldName, cluster);
        MonitorMetricModel<Date, Long> monitorMetricModel = new MonitorMetricModel<>(mapLong(result), "ms");
        return monitorMetricModel;
    }

    private MonitorMetricModel<Date, Double> getNodeJvmHeap(Template template, Map<String, Object> dataMap,  String fieldName, Cluster cluster)throws PallasException {
        List<MetricModel<Date, Double>> result = getMonitorMetricModels(template, dataMap,fieldName, cluster);
        result.forEach(value -> {
            value.setY(MetricConvertUtil.byteToMb(value.getY().longValue()));
        });
        MonitorMetricModel<Date, Double> monitorMetricModel = new MonitorMetricModel<>(result, "MB");
        //转换单位：double
        return monitorMetricModel;
    }

    private MonitorMetricModel<Date, Integer> getNodeHttpOpen(Template template, Map<String, Object> dataMap,  String fieldName, Cluster cluster)throws PallasException {
        List<MetricModel<Date, Double>> result = getMonitorMetricModels(template, dataMap,fieldName, cluster);
        MonitorMetricModel<Date, Integer> monitorMetricModel = new MonitorMetricModel<>(mapInteger(result), "");
        return monitorMetricModel;
    }

    private MonitorMetricModel<Date, Double> getNodeIndexMemory(Template template, Map<String, Object> dataMap,  String fieldName, Cluster cluster)throws PallasException {
        List<MetricModel<Date, Double>> result = getMonitorMetricModels(template, dataMap,fieldName, cluster);
        result.forEach(value -> {
            value.setY(MetricConvertUtil.byteToMb(value.getY().longValue()));
        });

        MonitorMetricModel<Date, Double> monitorMetricModel = new MonitorMetricModel<>(result, "MB");
        return monitorMetricModel;
    }


    private MonitorMetricModel<Date, Long> getNodeSegmentCount(Template template, Map<String, Object> dataMap,  String fieldName, Cluster cluster)throws PallasException {
        List<MetricModel<Date, Double>> result = getMonitorMetricModels(template, dataMap,fieldName, cluster);
        MonitorMetricModel<Date, Long> monitorMetricModel = new MonitorMetricModel<>(mapLong(result), "");
        return monitorMetricModel;
    }

    private MonitorMetricModel<Date, Integer> getNodeThreadpool(Template template, Map<String, Object> dataMap,  String fieldName, Cluster cluster)throws PallasException {
        List<MetricModel<Date, Double>> result = getMonitorMetricModels(template, dataMap,fieldName, cluster);
        MonitorMetricModel<Date, Integer> monitorMetricModel = new MonitorMetricModel<>(mapInteger(result), "");
        return monitorMetricModel;
    }

    /**
     *
     * index metric aggs
     *
     */

    private MonitorMetricModel<Date, Double> getIndex_memory(Template template, Map<String, Object> dataMap,  String fieldName, Cluster cluster)throws PallasException {
        List<MetricModel<Date, Double>> result = getMonitorMetricModels(template, dataMap,fieldName, cluster);
        result.forEach(value -> {
            value.setY(MetricConvertUtil.byteToMb(value.getY().longValue()));
        });

        MonitorMetricModel<Date, Double> monitorMetricModel = new MonitorMetricModel<>(result, "MB");
        return monitorMetricModel;
    }

    private MonitorMetricModel<Date, Long> getIndexSegmentCount(Template template, Map<String, Object> dataMap,  String fieldName, Cluster cluster)throws PallasException {
        List<MetricModel<Date, Double>> result = getMonitorMetricModels(template, dataMap,fieldName, cluster);
        MonitorMetricModel<Date, Long> monitorMetricModel = new MonitorMetricModel<>(mapLong(result), "");
        return monitorMetricModel;
    }

    private MonitorMetricModel<Date, Long> getIndexDocumentCount(Template template, Map<String, Object> dataMap,  String fieldName, Cluster cluster)throws PallasException {
        List<MetricModel<Date, Double>> result = getMonitorMetricModels(template, dataMap,fieldName, cluster);
        MonitorMetricModel<Date, Long> monitorMetricModel = new MonitorMetricModel<>(mapLong(result), "");
        return monitorMetricModel;
    }

    private MonitorMetricModel<Date, Double> getIndex_disk(Template template, Map<String, Object> dataMap,  String fieldName, Cluster cluster)throws PallasException {
        List<MetricModel<Date, Double>> result = getMonitorMetricModels(template, dataMap,fieldName, cluster);
        result.forEach(value -> {
            value.setY(MetricConvertUtil.byteToMb(value.getY().longValue()));
        });

        MonitorMetricModel<Date, Double> monitorMetricModel = new MonitorMetricModel<>(result, "MB");
        return monitorMetricModel;
    }


    /**
     *
     */
    private List<MetricModel<Date, Double>> getMonitorMetricModels(Template template, Map<String, Object> dataMap, String fieldName, Cluster cluster) throws PallasException{
        String stringResult = getMetricFromES(template, dataMap,fieldName, cluster);
        List<MetricModel<Date, Double>> result =  parseMetric(stringResult);
        return result;
    }

    /**
     *
     * @param template
     * @param dataMap
     * @param fieldName
     * @param cluster
     * @return
     */
    private String getMetricFromES(Template template, Map<String, Object> dataMap, String fieldName, Cluster cluster)  throws PallasException{
        StringWriter writer = new StringWriter();
        String result = "";
        if(StringUtils.isNotEmpty(fieldName)) {
            dataMap.put("fieldName", fieldName);
        }
        try {
            template.process(dataMap, writer);
            //查询模板内容
            String queryString = writer.toString();
            result = elasticSearchService.queryByDsl(queryString, getEndPoint(dataMap), cluster);
        } catch (TemplateException e) {
            logger.error("template:{} sth wrong", template.getName(), e);
            throw new PallasException("查询模板: " + template.getName() + "存在问题", e);
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            IOUtils.closeQuietly(writer);
        }

      //  parseMetric(result, 1.0);
        return result;
    }

    /**
     *
     * @param string
     * @return
     */

    private List<MetricModel<Date, Double>> parseMetric(String string) {
       // X:Date Y:
        List<MetricModel<Date, Double>> result = new ArrayList<>();

        JSONObject rootObj = JSON.parseObject(string);

        JSONArray keyValueArray = rootObj.getJSONObject("aggregations").getJSONObject("aggs_2_date_histogram").getJSONArray("buckets");

        List<JSONObject> keyValueList = keyValueArray.toJavaList(JSONObject.class);

        keyValueList.stream().forEach(jsonObject -> {
            Date date = jsonObject.getDate("key");
            Double value = jsonObject.getJSONObject("aggs_1_value").getDoubleValue("value");
            result.add(new MetricModel(date, value));
        });

        return result;
    }

    private static List<MetricModel<Date, Integer>> mapInteger(List<MetricModel<Date, Double>> models) {
        return Lists.transform(models, new Function<MetricModel<Date, Double>, MetricModel<Date, Integer>>() {

            @Override
            public MetricModel<Date, Integer> apply(MetricModel<Date, Double> input) {
                MetricModel<Date, Integer> model = new MetricModel<>();
                model.setX(input.getX());
                model.setY(input.getY().intValue());
                return model;
            }
        });
    }

    private static List<MetricModel<Date, Long>>mapLong(List<MetricModel<Date, Double>> models) {
        return Lists.transform(models, new Function<MetricModel<Date, Double>, MetricModel<Date, Long>>() {

            @Override
            public MetricModel<Date, Long> apply(MetricModel<Date, Double> input) {
                MetricModel<Date, Long> model = new MetricModel<>();
                model.setX(input.getX());
                model.setY(input.getY().longValue());
                return model;
            }
        });
    }

    private Cluster getCluster(String clusterId) throws PallasException{
        Cluster cluster =  clusterService.findByName(clusterId);
        if(null == cluster) {
            throw new PallasException("集群不存在：" + clusterId);
        };
        if(StringUtils.isNotEmpty(cluster.getRealClusters())) {
            throw new PallasException(clusterId + "是逻辑集群");
        }
        return cluster;
    }

    private String getIntevalString(long from , long to) {
        long dist = to - from;
        if(dist <= 1800000) {            //30m
            return "30s";
        } else if(dist <= 3600000) {    //1h
            return "30s";
        } else if(dist <= 10800000) {   //3h
            return "1m";
        } else if(dist <= 21600000) {   //6h
            return "2m";
        } else if(dist <= 43200000) {   //12h
            return "5m";
        } else if(dist <= 86400000) {   //24h
            return "10m";
        } else if(dist <= 259200000) {  //3d
            return "30m";
        }
        return "1h";
    }

    private Template getTempalte(String templateName) throws PallasException{
        Template template = null;
        try {
            template = freeMarkerBean.getObject().getTemplate(templateName);
            template.setNumberFormat("#");
        } catch (IOException e) {
            logger.error("tempalte: wrong", e);
            throw new PallasException("查询模板存在问题" , e);
        }
        return template;
    }
}
