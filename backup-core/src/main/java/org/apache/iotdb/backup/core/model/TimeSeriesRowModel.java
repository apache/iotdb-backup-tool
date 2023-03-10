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
package org.apache.iotdb.backup.core.model;

import java.util.List;

public class TimeSeriesRowModel {

  // 时间戳
  private String timestamp;
  // 设备信息
  private DeviceModel deviceModel;
  // 行数据
  private List<IField> iFieldList;

  public String getTimestamp() {
    return timestamp;
  }

  public void setTimestamp(String timestamp) {
    this.timestamp = timestamp;
  }

  public DeviceModel getDeviceModel() {
    return deviceModel;
  }

  public void setDeviceModel(DeviceModel deviceModel) {
    this.deviceModel = deviceModel;
  }

  public List<IField> getIFieldList() {
    return iFieldList;
  }

  public void setIFieldList(List<IField> iFieldList) {
    this.iFieldList = iFieldList;
  }
}
