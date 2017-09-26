/*
 * Copyright (c) 2017 VMWare Inc. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.hillview.table.api;

import org.hillview.dataset.api.IJson;

import javax.annotation.Nullable;
import java.io.Serializable;

public class ColumnNameAndConverter implements Serializable, IJson {
    public final String columnName;
    @Nullable
    public final IStringConverter converter;

    public ColumnNameAndConverter(String columnName, @Nullable IStringConverter converter) {
        this.columnName = columnName;
        this.converter = converter;
    }

    public ColumnNameAndConverter(String columnName) {
        this(columnName, null);
    }
}
