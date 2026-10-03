package com.zeus.framework.ai.agent;

import com.zeus.framework.ai.agent.workspace.InMemoryWorkspace;
import com.zeus.framework.ai.agent.workspace.WorkspaceTools;
import io.micrometer.observation.ObservationRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.model.ChatModel;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Koşu kapsamlı ajan yürütücüsü.
 *
 * <p><b>Her koşu kendi dünyasını kurar:</b> taze {@link InMemoryWorkspace}, taze
 * {@link WorkspaceTools}, taze {@link BudgetEligibilityChecker} ve taze {@link ChatClient}.
 * deepagents'ın LangGraph state kanallarıyla (ve private API'leriyle) çözdüğü şey burada nesne
 * ömrüyle çözülür; ayrıca bütçe sayacının koşular arasında sızması YAPISAL olarak imkânsız olur.
 *
 * <p>Tool döngüsünü Spring AI'ın {@code ToolCallingAdvisor}'ı yürütür — alt sınıflanmaz. Tek
 * müdahalemiz, {@code ChatClient.builder}'ın advisor-builder alan aşırı yüklemesine bütçe
 * checker'ını geçmek.
 */
public class DefaultZeusAgent implements ZeusAgent {

    private static final Logger log = LoggerFactory.getLogger(DefaultZeusAgent.class);

    private final ChatModel chatModel;
    private final ObservationRegistry observationRegistry;

    public DefaultZeusAgent(ChatModel chatModel, ObservationRegistry observationRegistry) {
        this.chatModel = chatModel;
        this.observationRegistry = observationRegistry == null
                ? ObservationRegistry.NOOP : observationRegistry;
    }

    @Override
    public AgentResult<String> run(String task, AgentSpec spec) {
        return calistir(task, spec, ChatClient.CallResponseSpec::content);
    }

    @Override
    public <T> AgentResult<T> runAs(String task, Class<T> type, AgentSpec spec) {
        return calistir(task, spec, cevap -> cevap.entity(type));
    }

    /**
     * Koşuya özgü dünyayı kurar (taze çalışma alanı + tool'lar + bütçe + ChatClient), çağrıyı
     * yapar ve istatistikleri toplar. {@code run} ve {@code runAs} yalnızca son çağrının
     * {@code content()} mi {@code entity(type)} mi olduğuyla ayrışır — unchecked cast'e gerek
     * yok, çünkü {@link Function}'ın jenerik tipi {@code T} zaten çağıran tarafından belirlenir.
     */
    private <T> AgentResult<T> calistir(
            String task, AgentSpec spec, Function<ChatClient.CallResponseSpec, T> cikti) {

        InMemoryWorkspace workspace = new InMemoryWorkspace();
        WorkspaceTools workspaceTools = new WorkspaceTools(workspace);
        BudgetEligibilityChecker butce =
                new BudgetEligibilityChecker(spec.budget(), System::currentTimeMillis);

        List<Object> tools = new ArrayList<>(spec.tools());
        tools.add(workspaceTools);

        ChatClient client = ChatClient
                .builder(chatModel, observationRegistry, null, null,
                        ToolCallingAdvisor.builder().toolExecutionEligibilityChecker(butce))
                .build();

        long basla = System.currentTimeMillis();
        T sonuc = null;
        StopReason sebep;
        try {
            sonuc = cikti.apply(client.prompt()
                    .system(spec.systemPrompt() == null ? "" : spec.systemPrompt())
                    .user(task)
                    .tools(tools.toArray())
                    .call());
            sebep = butce.stopReason();
        } catch (RuntimeException e) {
            // Koşu yarıda kaldıysa bile çalışma alanı DÖNER: o ana kadar yazılmış rapor kaybolmaz.
            log.warn("Ajan koşusu hata ile bitti: {}", e.getMessage(), e);
            sebep = StopReason.ERROR;
        }
        long sure = System.currentTimeMillis() - basla;

        AgentRunStats stats = new AgentRunStats(butce.steps(), butce.tokens(), sure, sebep);
        log.info("Ajan koşusu bitti — adım={}, token={}, süre={} ms, sebep={}, dosya={}",
                stats.steps(), stats.tokens(), stats.durationMs(), stats.stopReason(),
                workspace.snapshot().size());

        return new AgentResult<>(sonuc, workspace.snapshot(), stats);
    }
}
