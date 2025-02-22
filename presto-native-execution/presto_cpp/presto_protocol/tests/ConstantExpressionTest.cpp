/*
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
#include <gtest/gtest.h>

#include "presto_cpp/main/common/tests/test_json.h"
#include "presto_cpp/presto_protocol/core/presto_protocol_core.h"

using namespace facebook::presto::protocol;

class ConstantExpressionTest : public ::testing::Test {};

TEST_F(ConstantExpressionTest, basic) {
  std::string str = R"(
       {
           "@type":"constant",
           "valueBlock":"CgAAAExPTkdfQVJSQVkBAAAAAAAAAAAAAAAA",
           "type":"bigint"
        }
    )";

  json j = json::parse(str);
  ConstantExpression p = j;

  // Check some values ...
  ASSERT_EQ(p._type, "constant");
  ASSERT_EQ(p.type, "bigint");

  testJsonRoundtrip(j, p);
}
