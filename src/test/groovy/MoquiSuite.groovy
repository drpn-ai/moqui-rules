import org.junit.jupiter.api.AfterAll
import org.junit.platform.suite.api.SelectClasses
import org.junit.platform.suite.api.Suite
import org.moqui.Moqui

@Suite
@SelectClasses([ DroolsRuleTests.class, ComponentBootTests.class, StoreRuleTests.class, BuildFactTests.class, EvaluateRuleSetTests.class, ListFactTests.class, LearnFileFieldsTests.class, UpdateFactFieldTests.class, CheckFileTests.class, LineKindTests.class, FactTreeRefusalTests.class, HoldTaskTriageTests.class, FactValueTests.class ])
class MoquiSuite {
    @AfterAll
    static void destroyMoqui() { Moqui.destroyActiveExecutionContextFactory() }
}
