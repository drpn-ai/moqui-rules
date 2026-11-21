import org.junit.jupiter.api.AfterAll
import org.junit.platform.suite.api.SelectClasses
import org.junit.platform.suite.api.Suite
import org.moqui.Moqui

/** The specs that run on any Moqui runtime; specs that read an outside order-management entity model are in MoquiSuite only. */
@Suite
@SelectClasses([ DroolsRuleTests.class, StoreRuleTests.class, LearnFileFieldsTests.class, UpdateFactFieldTests.class, CheckFileTests.class, LineKindTests.class, FactValueTests.class, TenantFilterTests.class, BatchEvaluateTests.class, CompareFieldLineTests.class, PieceTests.class, ExplainTests.class, CacheClearTests.class, HostFilterTests.class, HostNeutralityTests.class, ListOperatorTests.class, NormalizedTextTests.class ])
class PortableSuite {
    @AfterAll
    static void destroyMoqui() { Moqui.destroyActiveExecutionContextFactory() }
}
