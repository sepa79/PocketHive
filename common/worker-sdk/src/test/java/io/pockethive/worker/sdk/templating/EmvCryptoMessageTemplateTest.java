package io.pockethive.worker.sdk.templating;

import static org.assertj.core.api.Assertions.assertThat;

import io.pockethive.templating.PebbleTemplateRenderer;
import io.pockethive.templating.api.DisabledSequenceAccess;
import io.pockethive.work.api.WorkItem;
import io.pockethive.work.api.WorkerInfo;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EmvCryptoMessageTemplateTest {
    @Test
    void rendersArqcFromWorkItemJsonAndScenarioVariables() {
        WorkItem seed = WorkItem.text(new WorkerInfo("templating", "swarm", "instance", null, null), """
            {"pan":"1234567890123456","panSeqNo":"00","emv":{
              "atc":"0FFF","un":"52BF4585",
              "arqcDataHex":"0000000099990000000000000840800004800008409912310152BF458518000FFF06011203A0B800"
            }}
            """)
            .header("vars", Map.of("imkAcHex", "0123456789ABCDEFFEDCBA9876543210"))
            .build();
        MessageTemplate template = MessageTemplate.builder()
            .bodyType(MessageBodyType.SIMPLE)
            .bodyTemplate("""
                {{ eval("'9F2608' + #iso9797_alg3_mac_m2(#emv_common_sk(#emv_icc_mk_a(vars.imkAcHex, #json_path(payload, '/pan'), #json_path(payload, '/panSeqNo')), #json_path(payload, '/emv/atc') + '000000000000'), #json_path(payload, '/emv/arqcDataHex'))") }}""")
            .build();
        MessageTemplateRenderer renderer = new MessageTemplateRenderer(
            new PebbleTemplateRenderer(DisabledSequenceAccess.INSTANCE));

        assertThat(renderer.render(template, seed).body()).isEqualTo("9F260857E5F123CB66DF65");
    }
}
