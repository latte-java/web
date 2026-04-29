@import org.lattejava.web.tests.jte.JTETemplatesTest.Model
@param Model model = null
@param Model model1 = null
@param Model model2 = null
${model != null ? "Template with model=" + model.name() : "Template model1=" + model1.name() + " model2=" + model2.name()}