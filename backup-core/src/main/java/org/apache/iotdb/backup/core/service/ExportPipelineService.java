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
package org.apache.iotdb.backup.core.service;

import org.apache.iotdb.backup.core.model.*;
import org.apache.iotdb.backup.core.pipeline.context.PipelineContext;
import org.apache.iotdb.backup.core.pipeline.context.model.ExportModel;
import org.apache.iotdb.backup.core.pipeline.context.model.FileSinkStrategyEnum;
import org.apache.iotdb.backup.core.utils.IotDBKeyWords;
import org.apache.iotdb.backup.core.utils.ToByteArrayUtils;
import org.apache.iotdb.rpc.IoTDBConnectionException;
import org.apache.iotdb.rpc.StatementExecutionException;
import org.apache.iotdb.session.Session;
import org.apache.iotdb.session.SessionDataSet;
import org.apache.iotdb.tsfile.compress.ICompressor;
import org.apache.iotdb.tsfile.exception.write.WriteProcessException;
import org.apache.iotdb.tsfile.file.metadata.enums.TSDataType;
import org.apache.iotdb.tsfile.read.common.Field;
import org.apache.iotdb.tsfile.read.common.RowRecord;
import org.apache.iotdb.tsfile.write.TsFileWriter;
import org.apache.iotdb.tsfile.write.record.Tablet;

import net.jpountz.lz4.LZ4Compressor;
import net.jpountz.lz4.LZ4Factory;
import org.apache.commons.collections4.ListUtils;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.QuoteMode;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xerial.snappy.Snappy;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** @Author: LL @Description: @Date: create in 2022/6/24 9:50 */
public class ExportPipelineService {

  private static final Logger log = LoggerFactory.getLogger(ExportPipelineService.class);

  private static final int T_LIMIT = 999;

  private static ExportPipelineService exportPipelineService;

  private ExportPipelineService() {}

  public static ExportPipelineService exportPipelineService() {
    if (exportPipelineService == null) {
      exportPipelineService = new ExportPipelineService();
    }
    return exportPipelineService;
  }

  /**
   * ??????????????????????????? PATH_FILENAME ???????????????path????????????????????? pc?????????????????????pc??????????????????????????? EXTRA_CATALOG
   * ???????????????????????????????????????????????????????????????????????????????????????path???????????????????????? ?????????????????????????????????outputstream?????????outputStreamMap???
   *
   * @param outputStreamMap
   * @param catalogName
   * @return
   */
  public Flux<String> parseFileSinkStrategy(
      ConcurrentHashMap<String, OutputStream> outputStreamMap, String catalogName) {
    return Flux.deferContextual(
        context -> {
          PipelineContext<ExportModel> pcontext = context.get("pipelineContext");
          ExportModel exportModel = pcontext.getModel();
          if (exportModel.getFileSinkStrategyEnum() == FileSinkStrategyEnum.EXTRA_CATALOG) {
            File file = new File(exportModel.getFileFolder());
            if (!file.exists()) {
              file.mkdirs();
            }
            String catalogFilePath = exportModel.getFileFolder() + catalogName;
            try {
              FileOutputStream out = new FileOutputStream(new File(catalogFilePath));
              String header = "FILE_NAME,ENTITY_PATH\r\n";
              out.write(header.getBytes());
              outputStreamMap.put("CATALOG", out);
            } catch (IOException e) {
              log.error("????????????:", e);
            }
          }
          return Flux.just(catalogName);
        });
  }

  /** ??????pcontext???????????????????????????????????????path???ep:root.**???, ????????????DeviceModel ??????????????????path???isAligned */
  public Flux<DeviceModel> parseToDeviceModel() {
    return Flux.deferContextual(
        context -> {
          PipelineContext<ExportModel> pcontext = context.get("pipelineContext");
          ExportModel exportModel = pcontext.getModel();
          String version = context.get("VERSION");
          return Flux.create(
              sink -> {
                StringBuilder sqlBuilder = new StringBuilder();
                sqlBuilder
                    .append("show devices ")
                    .append(formatPath(exportModel.getIotdbPath(), version));
                String sql = sqlBuilder.toString();
                try {
                  SessionDataSet deviceData = exportModel.getSession().executeQueryStatement(sql);
                  sink.onRequest(
                      n -> {
                        String aa = "";
                        for (int i = 0; i < n; i++) {
                          try {
                            if (deviceData.hasNext()) {
                              RowRecord rowRecord = deviceData.next();
                              int positon = deviceData.getColumnNames().indexOf("devices");
                              String deviceFullName =
                                  rowRecord.getFields().get(positon).getStringValue();
                              positon = deviceData.getColumnNames().indexOf("isAligned");
                              String isAligned = "false";
                              if (positon != -1) {
                                isAligned = rowRecord.getFields().get(positon).getStringValue();
                              }
                              DeviceModel deviceModel = new DeviceModel();
                              deviceModel.setDeviceName(deviceFullName);
                              deviceModel.setAligned(Boolean.parseBoolean(isAligned));
                              sink.next(deviceModel);
                            } else {
                              sink.complete();
                            }
                          } catch (StatementExecutionException | IoTDBConnectionException e) {
                            log.error("????????????:", e);
                          }
                        }
                      });
                } catch (StatementExecutionException | IoTDBConnectionException e) {
                  log.error("?????????????????????SQL???{}", sql);
                  sink.complete();
                }
              });
        });
  }

  public Flux<String> countDeviceNum(String s, Integer[] totalSize) {
    return Flux.deferContextual(
        context -> {
          PipelineContext<ExportModel> pcontext = context.get("pipelineContext");
          ExportModel exportModel = pcontext.getModel();
          String version = context.get("VERSION");
          StringBuilder sqlBuilder = new StringBuilder();
          sqlBuilder
              .append("count devices ")
              .append(formatPath(exportModel.getIotdbPath(), version));
          String sql = sqlBuilder.toString();
          try {
            SessionDataSet deviceData = exportModel.getSession().executeQueryStatement(sql);
            if (deviceData.hasNext()) {
              RowRecord rowRecord = deviceData.next();
              String count =
                  rowRecord
                      .getFields()
                      .get(deviceData.getColumnNames().indexOf("devices"))
                      .getStringValue();
              totalSize[0] = Integer.parseInt(count);
            }
          } catch (StatementExecutionException | IoTDBConnectionException e) {
            log.error("????????????sql:{},e : {}", sql, e);
          }
          return Flux.just(s);
        });
  }

  /**
   * ?????????????????????????????????????????????
   *
   * @param deviceModel
   * @return
   */
  public Flux<Pair<DeviceModel, List<TimeseriesModel>>> parseTimeseries(DeviceModel deviceModel) {
    return Flux.deferContextual(
        contextView -> {
          PipelineContext<ExportModel> pcontext = contextView.get("pipelineContext");
          ExportModel exportModel = pcontext.getModel();
          String version = contextView.get("VERSION");
          List<TimeseriesModel> timeseriesList = new ArrayList<>();
          Session session = exportModel.getSession();
          try {
            StringBuilder buffer = new StringBuilder();
            buffer
                .append(" show timeseries ")
                .append(formatPath(deviceModel.getDeviceName(), version))
                .append(".*");
            String sql = buffer.toString();
            SessionDataSet timeseriesSet = session.executeQueryStatement(sql);
            while (timeseriesSet.hasNext()) {
              RowRecord record = timeseriesSet.next();
              int position = timeseriesSet.getColumnNames().indexOf("timeseries");
              String measurement = record.getFields().get(position).getStringValue();
              TimeseriesModel timeseriesModel = new TimeseriesModel();
              timeseriesModel.setName(measurement);
              position = timeseriesSet.getColumnNames().indexOf("dataType");
              timeseriesModel.setType(
                  generateTSDataType(record.getFields().get(position).getStringValue()));
              timeseriesList.add(timeseriesModel);
            }
            // ??????
            if (exportModel.getMeasurementList() != null
                && exportModel.getMeasurementList().size() > 0) {
              List<String> queryMeasurementList =
                  exportModel.getMeasurementList().stream()
                      .map(
                          s -> {
                            StringBuilder builder = new StringBuilder();
                            builder.append(deviceModel.getDeviceName()).append(".").append(s);
                            return builder.toString();
                          })
                      .collect(Collectors.toList());
              timeseriesList =
                  timeseriesList.stream()
                      .filter(
                          timeseriesModel -> {
                            if (queryMeasurementList.contains(timeseriesModel.getName())) {
                              return true;
                            }
                            return false;
                          })
                      .collect(Collectors.toList());
            }
          } catch (StatementExecutionException | IoTDBConnectionException e) {
            log.error("????????????:", e);
          }
          return Flux.just(Pair.of(deviceModel, timeseriesList));
        });
  }

  /**
   * ??????entity ?????????????????????entity??????,?????????????????????row??????stream???
   *
   * @param pair
   * @return
   */
  public Flux<TimeSeriesRowModel> parseToRowModel(Pair<DeviceModel, List<TimeseriesModel>> pair) {
    return Flux.deferContextual(
        contextView -> {
          PipelineContext<ExportModel> pcontext = contextView.get("pipelineContext");
          ExportModel exportModel = pcontext.getModel();
          String version = contextView.get("VERSION");
          return Flux.create(
              (Consumer<FluxSink<TimeSeriesRowModel>>)
                  sink -> {
                    Session session = exportModel.getSession();
                    try {
                      createStreamData(
                          pair.getLeft(), pair.getRight(), session, sink, exportModel, version);
                    } catch (StatementExecutionException | IoTDBConnectionException e) {
                      log.error("????????????:", e);
                    }
                  });
        });
  }

  /**
   * ??????row???
   *
   * @param deviceModel
   * @param timeseries
   * @param session
   * @param sink
   * @param exportModel
   * @throws StatementExecutionException
   * @throws IoTDBConnectionException
   * @throws FileNotFoundException
   */
  private void createStreamData(
      DeviceModel deviceModel,
      List<TimeseriesModel> timeseries,
      Session session,
      FluxSink<TimeSeriesRowModel> sink,
      ExportModel exportModel,
      String version)
      throws StatementExecutionException, IoTDBConnectionException {

    List<List<TimeseriesModel>> groupTimeseriesList = ListUtils.partition(timeseries, T_LIMIT);
    List<SessionDataSet> dataSetList = new ArrayList<>();

    for (List<TimeseriesModel> l : groupTimeseriesList) {
      StringBuilder timeseriesBuffer = new StringBuilder();
      for (TimeseriesModel s : l) {
        if (timeseriesBuffer.length() == 0) {
          timeseriesBuffer.append(
              formatMeasurement(
                  s.getName().replace(deviceModel.getDeviceName() + ".", ""), version));
        } else {
          timeseriesBuffer
              .append(",")
              .append(
                  formatMeasurement(
                      s.getName().replace(deviceModel.getDeviceName() + ".", ""), version));
        }
      }
      StringBuilder sqlBuffer = new StringBuilder();
      sqlBuffer
          .append("select ")
          .append(timeseriesBuffer.toString())
          .append(" from ")
          .append(formatPath(deviceModel.getDeviceName(), version));
      if (exportModel.getWhereClause() != null && !"".equals(exportModel.getWhereClause())) {
        sqlBuffer.append(" where ").append(exportModel.getWhereClause());
      }
      String sql = sqlBuffer.toString();
      SessionDataSet deviceDetials = null;
      try {
        deviceDetials = session.executeQueryStatement(sql, Long.MAX_VALUE);
      } catch (Exception e) {
        log.error(sql);
        throw e;
      }
      dataSetList.add(deviceDetials);
    }
    sink.onRequest(
        n -> {
          synchronized (dataSetList) {
            Map<Long, List<IField>[]> sinkPoolMap = new TreeMap<>();
            Long mark = 0L; // ??????????????????sinkpoolMap??????????????????,?????????????????????????????????sink.next????????????????????????stream?????????
            Long stopMark = 0L; // ?????????????????????????????????????????????????????????????????????????????????,????????????????????????
            int loopMark =
                0; // ???????????????????????????????????????2??????????????????????????? loopMark=0???????????????????????????????????????????????????????????????????????????????????????????????? loopMark=1
            boolean stopFlag = false;
            if (dataSetList == null || dataSetList.size() == 0) {
              stopFlag = true;
            }
            int j = 0;
            try {
              // ??????????????????1000?????????row??????
              while (true) {
                for (int i = 0; i < dataSetList.size(); i++) {
                  SessionDataSet set = dataSetList.get(i);
                  mark =
                      recursionSessionData(
                          i,
                          loopMark,
                          mark,
                          dataSetList.size(),
                          set,
                          sinkPoolMap,
                          deviceModel,
                          timeseries);
                  if (i == dataSetList.size() - 1) {
                    if (stopMark.equals(mark)) {
                      if (loopMark == dataSetList.size() - 1) {
                        stopFlag = true;
                      }
                      loopMark++;
                    }
                    stopMark = mark;
                    for (Long key : sinkPoolMap.keySet()) {
                      if (key <= mark) {
                        try {
                          TimeSeriesRowModel rowModel =
                              conformToRowData(
                                  sinkPoolMap.get(key), groupTimeseriesList, deviceModel, key);
                          sinkPoolMap.remove(key);
                          sink.next(rowModel);
                          if (j == n) {
                            return;
                          }
                          j++;
                        } catch (Exception e) {
                          log.error("????????????:", e);
                        }
                      }
                    }
                  }
                }
                if (stopFlag) {
                  // ???????????????
                  TimeSeriesRowModel finishRowModel = new TimeSeriesRowModel();
                  DeviceModel finishDeviceModel = new DeviceModel();
                  StringBuilder builder = new StringBuilder();
                  builder.append("finish,").append(deviceModel.getDeviceName());
                  finishDeviceModel.setDeviceName(builder.toString());
                  finishRowModel.setDeviceModel(finishDeviceModel);
                  finishRowModel.setIFieldList(new ArrayList<>());
                  sink.next(finishRowModel);
                  sink.complete();
                  break;
                }
              }
            } catch (StatementExecutionException | IoTDBConnectionException e) {
              log.error("????????????:", e);
            }
          }
        });
  }

  /**
   * ??????????????????TimeSeriesRowModel????????? ???????????????Filed
   *
   * @param lists
   * @param groupTimeseriesList
   * @param deviceModel
   * @param timestamp
   * @return
   */
  private TimeSeriesRowModel conformToRowData(
      List<IField>[] lists,
      List<List<TimeseriesModel>> groupTimeseriesList,
      DeviceModel deviceModel,
      Long timestamp) {
    TimeSeriesRowModel model = new TimeSeriesRowModel();
    model.setDeviceModel(deviceModel);
    model.setTimestamp(String.valueOf(timestamp));
    for (int i = 0; i < groupTimeseriesList.size(); i++) {
      List<TimeseriesModel> timeseriesList = groupTimeseriesList.get(i);
      Map<String, TSDataType> dataTypeMap =
          timeseriesList.stream().collect(Collectors.toMap(s -> s.getName(), s -> s.getType()));
      if (model.getIFieldList() == null) {
        model.setIFieldList(new ArrayList<>());
      }
      if (lists[i] != null) {
        // ???????????????????????????  ??????tsDataType??????
        lists[i].stream()
            .forEach(
                iField -> {
                  TSDataType type = dataTypeMap.get(iField.getColumnName());
                  iField.setTsDataType(type);
                });
        model.getIFieldList().addAll(lists[i]);
      } else {
        List<IField> fillingEmptyIFieldList =
            timeseriesList.stream()
                .map(
                    s -> {
                      IField iField = new IField();
                      iField.setColumnName(s.getName());
                      iField.setTsDataType(s.getType());
                      return iField;
                    })
                .collect(Collectors.toList());
        model.getIFieldList().addAll(fillingEmptyIFieldList);
      }
    }
    return model;
  }

  /**
   * ??????????????????
   *
   * @param loopi ?????????????????????
   * @param loopMark ?????????????????????????????????????????????
   * @param mark ???????????????
   * @param size ?????????????????????
   * @param set ????????????????????????
   * @param sinkPoolMap
   * @return
   * @throws StatementExecutionException
   * @throws IoTDBConnectionException
   */
  private Long recursionSessionData(
      int loopi,
      int loopMark,
      Long mark,
      int size,
      SessionDataSet set,
      Map<Long, List<IField>[]> sinkPoolMap,
      DeviceModel deviceModel,
      List<TimeseriesModel> timeseries)
      throws StatementExecutionException, IoTDBConnectionException {
    if (set.hasNext()) {
      RowRecord rowRecord = set.next();
      List<String> columnName = set.getColumnNames();
      columnName.remove(0);
      List<IField> fieldList =
          columnName.stream()
              .map(
                  clname -> {
                    int position = columnName.indexOf(clname);
                    Field field = rowRecord.getFields().get(position);
                    IField iField = new IField();
                    FieldCopy fieldCopy = FieldCopy.copy(field);
                    if (fieldCopy.getDataType() == null) {
                      timeseries.stream()
                          .forEach(
                              timeseriesModel -> {
                                String colName =
                                    timeseriesModel
                                        .getName()
                                        .substring(deviceModel.getDeviceName().length() + 1);
                                if (colName.equals(clname)) {
                                  iField.setTsDataType(timeseriesModel.getType());
                                }
                              });
                    }
                    iField.setField(fieldCopy);
                    iField.setColumnName(clname);
                    return iField;
                  })
              .collect(Collectors.toList());
      // ???loopMark??????????????????????????????
      if (loopi == loopMark) {
        mark = rowRecord.getTimestamp();
      }
      if (sinkPoolMap.get(rowRecord.getTimestamp()) != null) {
        if (mark != 0 && rowRecord.getTimestamp() < mark) {
          recursionSessionData(
              loopi, loopMark, mark, size, set, sinkPoolMap, deviceModel, timeseries);
        }
        List<IField>[] array = sinkPoolMap.get(rowRecord.getTimestamp());
        if (array != null) {
          array[loopi] = fieldList;
          sinkPoolMap.putIfAbsent(rowRecord.getTimestamp(), array);
        }
      } else {
        if (mark != 0 && rowRecord.getTimestamp() < mark) {
          recursionSessionData(
              loopi, loopMark, mark, size, set, sinkPoolMap, deviceModel, timeseries);
        }
        List<IField>[] array = new List[size];
        array[loopi] = fieldList;
        sinkPoolMap.put(rowRecord.getTimestamp(), array);
      }
      return mark;
    }
    return mark;
  }

  /**
   * pipeline????????????????????? ?????????????????????pipeline?????????
   * ps???ExportSQLDataSource???ExportCsvDataSource???ExportCompressDataSource
   *
   * @param out
   */
  public void compress(byte[] buffer, OutputStream out) {
    try {
      out.write(buffer);
      out.flush();
    } catch (Throwable ex) {
      log.error("????????????:", ex);
    }
  }

  /**
   * ??????????????????????????????inputstream??? ????????????????????????????????????????????????????????????
   *
   * @param exportModel
   * @param list
   * @return
   * @throws IOException
   */
  public InputStream tranToInputStream(ExportModel exportModel, List<String> list)
      throws IOException {
    ByteArrayOutputStream byteOutputStream = new ByteArrayOutputStream();
    CSVPrinter printer =
        CSVFormat.Builder.create(CSVFormat.DEFAULT)
            .setHeader()
            .setSkipHeaderRecord(true)
            .setEscape('\\')
            .setQuoteMode(QuoteMode.NONE)
            .build()
            .print(new OutputStreamWriter(byteOutputStream, exportModel.getCharSet()));
    printer.printRecord(list);
    printer.flush();
    printer.close();
    ByteArrayInputStream in = new ByteArrayInputStream(byteOutputStream.toByteArray());
    return in;
  }

  public static String formatPath(String sql, String version) {
    if ("13".equals(version)) {
      return formatPath(sql);
    }
    return sql;
  }

  /**
   * timeseries ????????????????????????????????????iotdb?????????
   *
   * @param sql
   * @return
   */
  public static String formatPath(String sql) {
    String regx = "^[\\w._:@#{}$\\u2E80-\\u9FFF\"\'*\\\\]+$";
    String regxOnlyNum = "^[0-9]+$";
    char[] arr = sql.toCharArray();
    StringBuilder formatedSql = new StringBuilder();
    StringBuilder buffer = new StringBuilder();
    StringBuilder quoteCounter = new StringBuilder();
    for (int i = 0; i < arr.length; i++) {
      if ('\\' == arr[i] || '\"' == arr[i] || '\'' == arr[i]) {
        if (quoteCounter.length() == 0) {
          quoteCounter.append(arr[i]);
        } else {
          char quote = quoteCounter.charAt(quoteCounter.length() - 1);
          if ("\\".equals(quote)) {
            quoteCounter.deleteCharAt(quoteCounter.length() - 1);
          }
        }
      } else {
        if (quoteCounter.length() != 0) {
          quoteCounter.delete(0, quoteCounter.length());
        }
      }

      if ('.' == arr[i] || i == arr.length - 1) {
        if (buffer.length() != 0) {
          // quote ????????????
          if ('\"' == buffer.charAt(0) || '\'' == buffer.charAt(0)) {
            char pre = buffer.charAt(buffer.length() - 1);
            buffer.append(arr[i]);
            if (pre == buffer.charAt(0) && quoteCounter.length() == 0) {
              formatedSql.append(buffer);
              buffer.delete(0, buffer.length());
            }
          } else {
            // ?????????????????? iotdb ?????????????????????
            if (i == arr.length - 1) {
              buffer.append(arr[i]);
            }
            if (!Pattern.matches(regx, buffer.toString())
                || IotDBKeyWords.validateKeyWords(buffer.toString().toUpperCase())
                || Pattern.matches(regxOnlyNum, buffer.toString())) {
              if (i == arr.length - 1) {
                if (buffer.toString().startsWith("`") && buffer.toString().endsWith("`")) {
                  formatedSql.append(buffer);
                } else {
                  formatedSql.append("`").append(buffer).append("`");
                }
              } else {
                if (buffer.toString().startsWith("`") && buffer.toString().endsWith("`")) {
                  formatedSql.append(buffer).append(".");
                } else {
                  formatedSql.append("`").append(buffer).append("`").append(".");
                }
              }
            } else {
              if (i != arr.length - 1) {
                buffer.append(arr[i]);
              }
              formatedSql.append(buffer);
            }

            buffer.delete(0, buffer.length());
          }
        } else {
          if (!Pattern.matches(regx, buffer.toString())
              || IotDBKeyWords.validateKeyWords(buffer.toString().toUpperCase())) {
            buffer.append("`").append(arr[i]).append("`");
          } else if (Pattern.matches(regxOnlyNum, buffer.toString())) {
            buffer.append("`").append(arr[i]).append("`");
          } else {
            buffer.append(arr[i]);
          }
        }
      } else {
        buffer.append(arr[i]);
      }
    }
    formatedSql.append(buffer);
    return formatedSql.toString();
  }

  public static String formatMeasurement(String measurement, String version) {
    if ("13".equals(version)) {
      return formatMeasurement(measurement);
    }
    return measurement;
  }

  public static String formatMeasurement(String measurement) {
    String regx = "^[\\w._:@#{}$\\u2E80-\\u9FFF\"\'*\\\\]+$";
    String regxOnlyNum = "^[0-9]+$";
    StringBuilder builder = new StringBuilder();
    builder.append("`").append(measurement).append("`");
    if (measurement.startsWith("`") && measurement.endsWith("`")) {
      return measurement;
    }
    if (!Pattern.matches(regx, measurement)) {
      return builder.toString();
    }
    if (Pattern.matches(regxOnlyNum, measurement)) {
      return builder.toString();
    }
    if (IotDBKeyWords.validateKeyWords(measurement.toUpperCase())) {
      return builder.toString();
    }
    return measurement;
  }

  /**
   * csvprinter ???????????????????????????????????????????????????????????????????????????
   *
   * @param printer
   * @param values
   * @throws IOException
   */
  public void syncPrint(CSVPrinter printer, Iterable<?> values) throws IOException {
    synchronized (printer) {
      printer.printRecord(values);
    }
  }

  /**
   * csvprinter ???????????????????????????????????????????????????????????????????????????
   *
   * @param printer
   * @param values
   * @throws IOException
   */
  public void syncPrintRecoreds(CSVPrinter printer, Iterable<?> values) throws IOException {
    synchronized (printer) {
      printer.printRecords(values);
    }
  }

  public void syncWriteByTsfileWriter(TsFileWriter tsFileWriter, Tablet tablet, boolean isAligned)
      throws IOException, WriteProcessException {
    synchronized (tsFileWriter) {
      if (isAligned) {
        tsFileWriter.writeAligned(tablet);
      } else {
        tsFileWriter.write(tablet);
      }
    }
  }

  public void compressHeader(List<String> timeSeries, OutputStream outputStream, ExportModel model)
      throws IOException {
    // ?????? 4byte ?????? 2byte ???????????? 2byte
    String mo = "LOLI";
    short version = 1;
    String compressType = getCompressType(model);
    // mo version compressType
    byte[] mvc = ArrayUtils.addAll(mo.getBytes(), ToByteArrayUtils.shortToBytes(version));
    mvc = ArrayUtils.addAll(mvc, compressType.getBytes());

    // header byte?????? 4byte
    String[] timeSeriesArray = new String[timeSeries.size()];
    timeSeriesArray = timeSeries.toArray(timeSeriesArray);
    byte[] header = ToByteArrayUtils.getByteArray(timeSeriesArray);

    outputStream.write(mvc);
    outputStream.write(ToByteArrayUtils.intToBytes(header.length));
    outputStream.write(header);
  }

  public String getCompressType(ExportModel model) {
    String compressType;
    switch (model.getCompressEnum()) {
      case SNAPPY:
        compressType = "01";
        break;
      case GZIP:
        compressType = "02";
        break;
      case LZ4:
        compressType = "03";
        break;
      default:
        throw new IllegalArgumentException("compressEnum is illegal");
    }
    return compressType;
  }

  public void compressBlock(
      List<TimeSeriesRowModel> groupList, OutputStream outputStream, ExportModel exportModel) {
    // TODO: time ??????
    List<String> timeList = new ArrayList<>();
    // measurement ??????
    List<List<String>> list =
        groupList.stream()
            .map(
                s -> {
                  timeList.add(s.getTimestamp());
                  return s.getIFieldList().stream()
                      .map(IField::getField)
                      .collect(Collectors.toList());
                })
            .collect(
                ArrayList::new,
                (k, v) -> {
                  if (k.size() == 0) {
                    for (int i = 0; i < v.size(); i++) {
                      k.add(new ArrayList<>());
                    }
                  }
                  for (int i = 0; i < v.size(); i++) {
                    FieldCopy fieldCopy = v.get(i);
                    List<String> objectList = k.get(i);
                    if (fieldCopy == null
                        || fieldCopy.getObjectValue(fieldCopy.getDataType()) == null) {
                      objectList.add(null);
                    } else {
                      objectList.add(
                          String.valueOf(fieldCopy.getObjectValue(fieldCopy.getDataType())));
                    }
                  }
                },
                (k, v) -> {
                  for (int i = 0; i < k.size(); i++) {
                    k.get(i).addAll(v.get(i));
                  }
                });

    String blockHeader = "block";
    try {
      outputStream.write(blockHeader.getBytes());
    } catch (IOException e) {
      log.error("", e);
    }

    writeCompressData(timeList, outputStream, exportModel);

    for (int i = 0; i < list.size(); i++) {
      List<String> measurementList = list.get(i);
      writeCompressData(measurementList, outputStream, exportModel);
    }
  }

  public void writeCompressData(List<String> list, OutputStream out, ExportModel exportModel) {

    String[] ar = new String[list.size()];
    ar = list.toArray(ar);
    byte[] originalBytes = ToByteArrayUtils.getByteArray(ar);
    try {
      byte[] nowBytes;
      switch (exportModel.getCompressEnum()) {
        case SNAPPY:
          nowBytes = Snappy.compress(originalBytes);
          break;
        case LZ4:
          LZ4Factory factory = LZ4Factory.fastestInstance();
          LZ4Compressor compressor = factory.fastCompressor();
          nowBytes = compressor.compress(originalBytes);
          break;
        default:
          nowBytes = ICompressor.GZIPCompress.compress(originalBytes);
      }
      out.write(ToByteArrayUtils.intToBytes(originalBytes.length));
      out.write(ToByteArrayUtils.intToBytes(nowBytes.length));
      out.write(nowBytes);
      out.flush();
    } catch (IOException e) {
      log.error("", e);
    }
  }

  public TSDataType generateTSDataType(String typeValue) {
    if (typeValue.equals("BOOLEAN")) {
      return TSDataType.BOOLEAN;
    }

    if (typeValue.equals("INT32")) {
      return TSDataType.INT32;
    }

    if (typeValue.equals("INT64")) {
      return TSDataType.INT64;
    }

    if (typeValue.equals("FLOAT")) {
      return TSDataType.FLOAT;
    }

    if (typeValue.equals("DOUBLE")) {
      return TSDataType.DOUBLE;
    }

    if (typeValue.equals("TEXT")) {
      return TSDataType.TEXT;
    }

    if (typeValue.equals("VECTOR")) {
      return TSDataType.VECTOR;
    }
    return null;
  }
}
