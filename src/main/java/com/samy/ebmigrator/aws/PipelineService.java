package com.samy.ebmigrator.aws;

import software.amazon.awssdk.services.codepipeline.model.ActionCategory;
import software.amazon.awssdk.services.codepipeline.model.ActionDeclaration;
import software.amazon.awssdk.services.codepipeline.model.PipelineDeclaration;
import software.amazon.awssdk.services.codepipeline.model.PipelineSummary;
import software.amazon.awssdk.services.codepipeline.model.StageDeclaration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reapunta la etapa Deploy de CodePipeline al nuevo environment tras un swap.
 *
 * Cada environment con CI/CD se referencia en su pipeline por NOMBRE (clave
 * {@code EnvironmentName} de la acción Deploy de Elastic Beanstalk). Tras el swap
 * los nombres quedan cruzados y el viejo se terminará, así que el próximo deploy
 * fallaría si el pipeline sigue apuntando al environment viejo. Este servicio
 * localiza esas acciones y cambia solo el {@code EnvironmentName} (cambio quirúrgico),
 * dejando intacto el resto del pipeline (Source, Build, token OAuth, etc.).
 */
public final class PipelineService {

    /** Proveedor de la acción Deploy de Elastic Beanstalk en CodePipeline. */
    private static final String EB_PROVIDER = "ElasticBeanstalk";
    /** Clave de configuración de la acción Deploy que apunta al environment. */
    private static final String ENV_KEY = "EnvironmentName";
    /** Clave de configuración con el nombre de la aplicación de EB. */
    private static final String APP_KEY = "ApplicationName";

    private final AwsClients aws;

    public PipelineService(AwsClients aws) {
        this.aws = aws;
    }

    /** Una acción Deploy de EB encontrada en un pipeline, con el environment al que apunta. */
    public static final class DeployActionMatch {
        private final String pipeline;
        private final String stage;
        private final String action;
        private final String application;
        private final String environment;

        DeployActionMatch(String pipeline, String stage, String action, String application, String environment) {
            this.pipeline = pipeline;
            this.stage = stage;
            this.action = action;
            this.application = application;
            this.environment = environment;
        }

        public String pipeline() { return pipeline; }
        public String stage() { return stage; }
        public String action() { return action; }
        public String application() { return application; }
        public String environment() { return environment; }
    }

    /**
     * Recorre TODOS los pipelines de la cuenta/región y devuelve las acciones Deploy de
     * Elastic Beanstalk que apuntan a {@code envName}. Solo lectura.
     */
    public List<DeployActionMatch> findDeployActionsForEnvironment(String envName) {
        List<DeployActionMatch> matches = new ArrayList<>();
        for (PipelineSummary summary : aws.codePipeline().listPipelinesPaginator().pipelines()) {
            String pipelineName = summary.name();
            PipelineDeclaration pipeline = aws.codePipeline()
                    .getPipeline(b -> b.name(pipelineName)).pipeline();
            for (StageDeclaration stage : pipeline.stages()) {
                for (ActionDeclaration action : stage.actions()) {
                    if (isEbDeploy(action) && envName.equals(action.configuration().get(ENV_KEY))) {
                        matches.add(new DeployActionMatch(
                                pipelineName, stage.name(), action.name(),
                                action.configuration().get(APP_KEY), envName));
                    }
                }
            }
        }
        return matches;
    }

    /**
     * Reapunta en {@code pipelineName} todas las acciones Deploy de EB que apuntan a
     * {@code oldEnv} para que apunten a {@code newEnv}. Antes de modificar nada escribe
     * un backup en {@code backupFile}. Devuelve cuántas acciones se cambiaron.
     */
    public int repoint(String pipelineName, String oldEnv, String newEnv, Path backupFile) {
        PipelineDeclaration pipeline = aws.codePipeline()
                .getPipeline(b -> b.name(pipelineName)).pipeline();

        // Backup antes de tocar nada.
        writeBackup(backupFile, pipelineName, pipeline);

        int changed = 0;
        List<StageDeclaration> newStages = new ArrayList<>();
        for (StageDeclaration stage : pipeline.stages()) {
            List<ActionDeclaration> newActions = new ArrayList<>();
            for (ActionDeclaration action : stage.actions()) {
                if (isEbDeploy(action) && oldEnv.equals(action.configuration().get(ENV_KEY))) {
                    Map<String, String> cfg = new HashMap<>(action.configuration());
                    cfg.put(ENV_KEY, newEnv);
                    newActions.add(action.toBuilder().configuration(cfg).build());
                    changed++;
                } else {
                    newActions.add(action);
                }
            }
            newStages.add(stage.toBuilder().actions(newActions).build());
        }

        if (changed == 0) {
            return 0;
        }

        // Cambio quirúrgico: reenviamos el mismo PipelineDeclaration con solo los stages
        // modificados; el resto (roles, artifactStore, Source, Build…) se conserva igual.
        PipelineDeclaration updated = pipeline.toBuilder().stages(newStages).build();
        aws.codePipeline().updatePipeline(b -> b.pipeline(updated));
        return changed;
    }

    private static boolean isEbDeploy(ActionDeclaration action) {
        return action.actionTypeId() != null
                && action.actionTypeId().category() == ActionCategory.DEPLOY
                && EB_PROVIDER.equalsIgnoreCase(action.actionTypeId().provider())
                && action.configuration() != null
                && action.configuration().containsKey(ENV_KEY);
    }

    /**
     * Escribe un backup legible con el pipeline y sus acciones Deploy de EB (stage, acción,
     * app y environment). No es el wire-format exacto del CLI, pero registra lo necesario
     * para revertir a mano. El reapuntado, además, es simétrico (basta invertir --from/--to).
     */
    private static void writeBackup(Path file, String pipelineName, PipelineDeclaration pipeline) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"pipeline\": ").append(quote(pipelineName)).append(",\n");
        sb.append("  \"backedUpAt\": ").append(quote(Instant.now().toString())).append(",\n");
        sb.append("  \"deployActions\": [\n");
        List<String> rows = new ArrayList<>();
        for (StageDeclaration stage : pipeline.stages()) {
            for (ActionDeclaration action : stage.actions()) {
                if (isEbDeploy(action)) {
                    rows.add(String.format(
                            "    { \"stage\": %s, \"action\": %s, \"application\": %s, \"environment\": %s }",
                            quote(stage.name()), quote(action.name()),
                            quote(action.configuration().get(APP_KEY)),
                            quote(action.configuration().get(ENV_KEY))));
                }
            }
        }
        sb.append(String.join(",\n", rows)).append("\n");
        sb.append("  ]\n");
        sb.append("}\n");
        try {
            Files.write(file, sb.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("No se pudo escribir el backup en " + file + ": " + e.getMessage(), e);
        }
    }

    private static String quote(String s) {
        if (s == null) return "null";
        return '"' + s.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }
}
