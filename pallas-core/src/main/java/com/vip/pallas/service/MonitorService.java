package com.vip.pallas.service;

import com.vip.pallas.bean.monitor.*;
import com.vip.pallas.exception.PallasException;

import java.util.List;
import java.util.Map;

public interface MonitorService {

    ClusterMetricInfoModel queryClusterMetrics(MonitorQueryModel queryModel) throws Exception;

    NodeMetricInfoModel queryNodeMetrics(MonitorQueryModel queryModel) throws Exception;

    IndexMetricInfoModel queryIndexMetrices(MonitorQueryModel queryModel) throws Exception;

    ClusterGaugeMetricModel queryClusterInfo(MonitorQueryModel queryModel) throws Exception;

    List<NodeGaugeMetricModel> queryNodesInfo(MonitorQueryModel queryModel) throws Exception;

    List<IndexGaugeMetricModel> queryIndicesInfo(MonitorQueryModel queryModel) throws Exception;

    Integer getNodeCount(String clusterName) throws Exception;

    Integer getIndexCount(String clusterName) throws Exception;
}
