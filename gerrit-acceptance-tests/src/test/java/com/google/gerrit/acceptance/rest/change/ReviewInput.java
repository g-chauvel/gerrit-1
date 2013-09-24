// Copyright (C) 2013 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.gerrit.acceptance.rest.change;

import com.google.common.collect.Maps;

import java.util.Map;

public class ReviewInput {
  Map<String, Integer> labels;

  public static ReviewInput approve() {
    ReviewInput in = new ReviewInput();
    in.labels = Maps.newHashMap();
    in.labels.put("Code-Review", 2);
    return in;
  }
}
