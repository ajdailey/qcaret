package io.github.ajdailey.qcaret.app.view.archfixture;

import io.github.ajdailey.qcaret.app.controller.ApplicationController;

/** View contract classes that each break one architecture rule; never instantiated. */
final class ViewContractViolations {
  private ViewContractViolations() {}

  /** Uses a controller. */
  static final class UsesController {
    boolean quitting(ApplicationController controller) {
      return controller.isQuitting();
    }
  }
}
