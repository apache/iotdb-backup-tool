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
package org.apache.iotdb.backup.core.utils;

import java.io.IOException;
import java.io.ObjectOutputStream;
import java.io.OutputStream;

/** @Author: LL @Description: @Date: create in 2022/7/19 15:14 */
public class NoHeaderObjectOutputStream extends ObjectOutputStream {
  public NoHeaderObjectOutputStream(OutputStream out) throws IOException {
    super(out);
  }

  // do not wirte the header
  protected void writeStreamHeader() throws IOException {}
}
