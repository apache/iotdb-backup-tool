/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.iotdb.backup.core.pipeline.in.sink;

import org.apache.iotdb.backup.core.model.FieldCopy;
import org.apache.iotdb.backup.core.model.IField;
import org.apache.iotdb.backup.core.model.TimeSeriesRowModel;
import org.apache.iotdb.backup.core.pipeline.PipeSink;
import org.apache.iotdb.backup.core.pipeline.context.PipelineContext;
import org.apache.iotdb.backup.core.pipeline.context.model.ImportModel;
import org.apache.iotdb.rpc.IoTDBConnectionException;
import org.apache.iotdb.rpc.StatementExecutionException;
import org.apache.iotdb.session.Session;
import org.apache.iotdb.tsfile.file.metadata.enums.TSDataType;

import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.GroupedFlux;
import reactor.core.publisher.ParallelFlux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.stream.Collectors;

public class InRowModelFileSink extends PipeSink<TimeSeriesRowModel, TimeSeriesRowModel> {

  private static final Logger log = LoggerFactory.getLogger(InRowModelFileSink.class);

  private String name;

  private AtomicInteger finishedFileNum = new AtomicInteger();

  private Integer[] totalFileNum = new Integer[1];

  private AtomicLong finishedRowNum = new AtomicLong();

  /**
   * ?????????'rails'?????????????????????buffer???????????????????????? rails ????????????????????????????????????????????????????????????????????? ????????????????????????iotdb java?????????????????????????????????
   * String deviceId??? ???????????? List<Long> timeSeriesList = new ArrayList<>(); ???????????????timeseries
   * List<List<String>> measurementsList = new ArrayList<>(); ???????????????measurement???List<String>
   * ???????????????measurement List<List<TSDataType>> typesList = new ArrayList<>(); ?????? List<List<Object>>
   * valuesList = new ArrayList<>(); ??????
   *
   * @return
   */
  @Override
  public Function<ParallelFlux<TimeSeriesRowModel>, ParallelFlux<TimeSeriesRowModel>> doExecute() {
    return sink ->
        sink.transformGroups(
            (Function<
                    GroupedFlux<Integer, TimeSeriesRowModel>,
                    Publisher<? extends TimeSeriesRowModel>>)
                integerTimeSeriesRowModelGroupedFlux ->
                    integerTimeSeriesRowModelGroupedFlux
                        .buffer(10000, 10000)
                        .flatMap(
                            allList -> {
                              return Flux.deferContextual(
                                  contextView -> {
                                    PipelineContext<ImportModel> context =
                                        contextView.get("pipelineContext");
                                    totalFileNum = contextView.get("totalSize");
                                    ImportModel importModel = context.getModel();
                                    Session session = importModel.getSession();
                                    // reactor ??????????????????????????????????????????deviceId???????????????
                                    Map<String, List<TimeSeriesRowModel>> groupMap =
                                        allList.stream()
                                            .collect(
                                                Collectors.toMap(
                                                    k -> k.getDeviceModel().getDeviceName(),
                                                    p -> {
                                                      List<TimeSeriesRowModel> result =
                                                          new ArrayList();
                                                      result.add(p);
                                                      return result;
                                                    },
                                                    (o, n) -> {
                                                      o.addAll(n);
                                                      return o;
                                                    }));
                                    for (String groupKey : groupMap.keySet()) {
                                      if (groupKey.startsWith("finish")) {
                                        finishedFileNum.incrementAndGet();
                                        continue;
                                      }
                                      List<TimeSeriesRowModel> list = groupMap.get(groupKey);
                                      String deviceId =
                                          list.get(0).getDeviceModel().getDeviceName();
                                      Boolean isAligned = list.get(0).getDeviceModel().isAligned();

                                      List<Long> timeSeriesList = new ArrayList<>();
                                      List<List<String>> measurementsList = new ArrayList<>();
                                      List<List<TSDataType>> typesList = new ArrayList<>();
                                      List<List<Object>> valuesList = new ArrayList<>();

                                      list.stream()
                                          .forEach(
                                              rowModel -> {
                                                List<String> rowMeasurementList = new ArrayList<>();
                                                List<TSDataType> rowDataTypeList =
                                                    new ArrayList<>();
                                                List<Object> rowValueList = new ArrayList<>();
                                                timeSeriesList.add(
                                                    Long.parseLong(rowModel.getTimestamp()));
                                                for (IField iField : rowModel.getIFieldList()) {
                                                  if (iField.getField() != null) {
                                                    FieldCopy field = iField.getField();
                                                    rowMeasurementList.add(iField.getColumnName());
                                                    rowDataTypeList.add(field.getDataType());
                                                    if (field.getDataType() == TSDataType.TEXT) {
                                                      rowValueList.add(field.getStringValue());
                                                    } else {
                                                      rowValueList.add(
                                                          field.getObjectValue(
                                                              field.getDataType()));
                                                    }
                                                  }
                                                }
                                                measurementsList.add(rowMeasurementList);
                                                typesList.add(rowDataTypeList);
                                                valuesList.add(rowValueList);
                                              });
                                      try {
                                        if (isAligned) {
                                          session.insertAlignedRecordsOfOneDevice(
                                              deviceId,
                                              timeSeriesList,
                                              measurementsList,
                                              typesList,
                                              valuesList);
                                        } else {
                                          session.insertRecordsOfOneDevice(
                                              deviceId,
                                              timeSeriesList,
                                              measurementsList,
                                              typesList,
                                              valuesList);
                                        }
                                      } catch (IoTDBConnectionException
                                          | StatementExecutionException e) {
                                        log.error("????????????:", e);
                                      }
                                      finishedRowNum.addAndGet(list.size());
                                    }
                                    return Flux.fromIterable(allList);
                                  });
                            }));
  }

  @Override
  public Double[] rateOfProcess() {
    log.info("?????????????????????{}", finishedFileNum);
    log.info("???????????????{}", totalFileNum[0]);
    Double[] rateDouble = new Double[2];
    rateDouble[0] = finishedFileNum.doubleValue();
    rateDouble[1] = Double.parseDouble(String.valueOf(totalFileNum[0]));
    return rateDouble;
  }

  @Override
  public Long finishedRowNum() {
    return finishedRowNum.get();
  }

  public InRowModelFileSink(String name) {
    this.name = name;
  }
}
