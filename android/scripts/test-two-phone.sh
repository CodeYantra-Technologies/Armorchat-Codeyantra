#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
./gradlew :app:testDebugUnitTest -PtwoPhoneTests \
  --tests com.codeyantra.armorchat.messaging.TwoPhoneMessagingTest \
  --tests com.codeyantra.armorchat.messaging.RouteInvitationTest \
  --tests com.codeyantra.armorchat.messaging.InnerRecordTest \
  --tests com.codeyantra.armorchat.messaging.MessageLifecycleTest \
  --tests com.codeyantra.armorchat.messaging.OutboxPolicyTest \
  --tests com.codeyantra.armorchat.messaging.ReceiptsAndRetryTest
