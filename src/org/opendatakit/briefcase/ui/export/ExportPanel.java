/*
 * Copyright (C) 2018 Nafundi
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package org.opendatakit.briefcase.ui.export;

import static java.time.format.DateTimeFormatter.ISO_DATE_TIME;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toList;
import static org.opendatakit.briefcase.export.ExportForms.buildCustomConfPrefix;
import static org.opendatakit.briefcase.model.FormStatus.TransferType.EXPORT;
import static org.opendatakit.briefcase.ui.ODKOptionPane.showErrorDialog;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.stream.Stream;
import org.bushe.swing.event.annotation.AnnotationProcessor;
import org.bushe.swing.event.annotation.EventSubscriber;
import org.opendatakit.briefcase.export.ExportAction;
import org.opendatakit.briefcase.export.ExportConfiguration;
import org.opendatakit.briefcase.export.ExportForms;
import org.opendatakit.briefcase.model.BriefcaseFormDefinition;
import org.opendatakit.briefcase.model.BriefcasePreferences;
import org.opendatakit.briefcase.model.FormStatus;
import org.opendatakit.briefcase.model.FormStatusEvent;
import org.opendatakit.briefcase.model.SavePasswordsConsentGiven;
import org.opendatakit.briefcase.model.SavePasswordsConsentRevoked;
import org.opendatakit.briefcase.model.TerminationFuture;
import org.opendatakit.briefcase.model.TransferSucceededEvent;
import org.opendatakit.briefcase.transfer.NewTransferAction;
import org.opendatakit.briefcase.ui.export.components.ConfigurationPanel;
import org.opendatakit.briefcase.util.FileSystemUtils;

public class ExportPanel {
  public static final String TAB_NAME = "Export";

  private final TerminationFuture terminationFuture;
  private final ExportForms forms;
  private final ExportPanelForm form;

  public ExportPanel(TerminationFuture terminationFuture, ExportForms forms, ExportPanelForm form, BriefcasePreferences preferences, Executor backgroundExecutor) {
    this.terminationFuture = terminationFuture;
    this.forms = forms;
    this.form = form;
    AnnotationProcessor.process(this);// if not using AOP

    form.getConfPanel().onChange(() ->
        forms.updateDefaultConfiguration(form.getConfPanel().getConfiguration())
    );

    forms.onSuccessfulExport((String formId, LocalDateTime exportDateTime) ->
        preferences.put(ExportForms.buildExportDateTimePrefix(formId), exportDateTime.format(ISO_DATE_TIME))
    );

    form.onChange(() -> {
      // Clean all default conf keys
      preferences.removeAll(ExportConfiguration.keys());

      // Put default conf
      if (form.getConfPanel().isValid())
        preferences.putAll(form.getConfPanel().getConfiguration().asMap());

      // Clean all custom conf keys
      forms.forEach(formId ->
          preferences.removeAll(ExportConfiguration.keys(buildCustomConfPrefix(formId)))
      );

      // Put custom confs
      forms.getCustomConfigurations().forEach((formId, configuration) ->
          preferences.putAll(configuration.asMap(buildCustomConfPrefix(formId)))
      );

      if (forms.someSelected() && (form.getConfPanel().isValid() || forms.allSelectedFormsHaveConfiguration()))
        form.enableExport();
      else
        form.disableExport();

      if (forms.allSelected()) {
        form.toggleClearAll();
      } else {
        form.toggleSelectAll();
      }
    });


    form.onExport(() -> backgroundExecutor.execute(() -> {
      // Segregating this validation from the export process to move it to ExportConfiguration on the future
      List<String> errors = forms.getSelectedForms().stream().flatMap(formStatus -> {
        ExportConfiguration exportConfiguration = forms.getConfiguration(formStatus.getFormDefinition().getFormId());
        boolean needsPemFile = ((BriefcaseFormDefinition) formStatus.getFormDefinition()).isFileEncryptedForm() || ((BriefcaseFormDefinition) formStatus.getFormDefinition()).isFieldEncryptedForm();

        if (needsPemFile && !exportConfiguration.isPemFilePresent())
          return Stream.of("The form " + formStatus.getFormName() + " is encrypted and you haven't set a PEM file");
        if (needsPemFile)
          return ExportAction.readPemFile(exportConfiguration.getPemFile()
              .orElseThrow(() -> new RuntimeException("PEM file not present"))
          ).getErrors().stream();
        return Stream.empty();
      }).collect(toList());

      if (errors.isEmpty())
        export();
      else
        showErrorDialog(getForm().getContainer(), errors.stream().collect(joining("\n")), "Export errors");
    }));
  }

  public static ExportPanel from(TerminationFuture terminationFuture, BriefcasePreferences exportPreferences, BriefcasePreferences appPreferences, Executor backgroundExecutor) {
    ExportConfiguration defaultConfiguration = ExportConfiguration.load(exportPreferences);
    ConfigurationPanel confPanel = ConfigurationPanel.from(defaultConfiguration, false, false);
    ExportForms forms = ExportForms.load(defaultConfiguration, getFormsFromStorage(), exportPreferences, appPreferences);
    ExportPanelForm form = ExportPanelForm.from(forms, confPanel);
    return new ExportPanel(
        terminationFuture,
        forms,
        form,
        exportPreferences,
        backgroundExecutor
    );
  }

  public void updateForms() {
    forms.merge(getFormsFromStorage());
    form.refresh();
  }

  private static List<FormStatus> getFormsFromStorage() {
    return FileSystemUtils.formCache.getForms().stream()
        .map(formDefinition -> new FormStatus(EXPORT, formDefinition))
        .collect(toList());
  }

  public ExportPanelForm getForm() {
    return form;
  }

  private void export() {
    form.disableUI();
    terminationFuture.reset();
    forms.getSelectedForms()
        .parallelStream()
        .forEach(form -> {
          String formId = form.getFormDefinition().getFormId();
          ExportConfiguration configuration = forms.getConfiguration(formId);
          if (configuration.getPullBefore().orElse(false))
            forms.getTransferSettings(formId).ifPresent(sci -> NewTransferAction.transferServerToBriefcase(
                sci,
                terminationFuture,
                Collections.singletonList(form)
            ));
          ExportAction.export(
              (BriefcaseFormDefinition) form.getFormDefinition(),
              configuration,
              terminationFuture
          );
        });
    form.enableUI();
  }

  @EventSubscriber(eventClass = FormStatusEvent.class)
  public void onFormStatusEvent(FormStatusEvent event) {
    updateForms();
  }

  @EventSubscriber(eventClass = TransferSucceededEvent.class)
  public void successfulCompletion(TransferSucceededEvent event) {
    if (BriefcasePreferences.getStorePasswordsConsentProperty())
      event.formsToTransfer.forEach(form -> forms.putTransferSettings(form, event.transferSettings));
  }

  @EventSubscriber(eventClass = SavePasswordsConsentGiven.class)
  public void onSavePasswordsConsentGiven(SavePasswordsConsentGiven event) {
    forms.flushTransferSettings();
  }

  @EventSubscriber(eventClass = SavePasswordsConsentRevoked.class)
  public void onSavePasswordsConsentRevoked(SavePasswordsConsentRevoked event) {
    forms.flushTransferSettings();
  }
}
