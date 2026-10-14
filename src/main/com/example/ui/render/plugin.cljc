(ns com.example.ui.render.plugin
  "The app's RAD renderer. Requiring this namespace registers `defmethod`s for every
   RAD rendering multimethod (fields, form layout, report layout, controls).

   This is a copy of `com.fulcrologic.rad.rendering.headless` from fulcro-rad-statecharts:
   plain HTML, no CSS, no component library. It is yours to change. Restyle these
   namespaces with your design system (DaisyUI, Bootstrap, plain CSS...) instead of
   pulling in a plugin library.

   Keep the `data-rad-type` / `data-rad-*` attributes when you restyle: headless tests
   (see `src/test`) find elements through them.

   Do NOT also require `com.fulcrologic.rad.rendering.headless.plugin`: both register
   `:default` methods, so whichever loads last silently wins."
  (:require
    ;; Requiring these namespaces registers all defmethod implementations
    [com.example.ui.render.controls]
    [com.example.ui.render.field]
    [com.example.ui.render.form]
    [com.example.ui.render.report]))
