package org.ddd.cp.ddd.runtime.registry;

import org.ddd.cp.ddd.model.ExtTimeoutException;
import org.ddd.cp.ddd.runtime.DDD;
import org.ddd.cp.ddd.runtime.registry.mock.MockStartupListener;
import org.ddd.cp.ddd.runtime.registry.mock.ability.*;
import org.ddd.cp.ddd.runtime.registry.mock.ext.IMultiMatchExt;
import org.ddd.cp.ddd.runtime.registry.mock.model.FooModel;
import org.ddd.cp.ddd.runtime.registry.mock.pattern.extension.B2BMultiMatchExt;
import org.ddd.cp.ddd.runtime.registry.mock.project.FooProject;
import org.ddd.cp.ddd.runtime.registry.mock.service.FooDomainService;
import org.ddd.cp.ddd.runtime.registry.mock.step.*;
import lombok.extern.slf4j.Slf4j;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

@RunWith(SpringJUnit4ClassRunner.class)
@ContextConfiguration(locations = {"classpath*:spring-test.xml"})
@Slf4j
public class IntegrationTest {

    @Resource
    private FooDomainService fooDomainService;

    @Autowired
    protected ApplicationContext ctx;

    @Resource
    private SubmitStepsExec submitStepsExec;

    private FooModel fooModel;

    @Resource
    private MockStartupListener startupListener;

    @Before
    public void setUp() {
        fooModel = new FooModel();
        fooModel.setProjectCode(FooProject.CODE);
        fooModel.setB2c(true);
    }

    @Test
    public void startupListener() {
        assertTrue(startupListener.isCalled());
    }

    @Test
    public void parsePropertiesKey() {
        String jsfAlias = "${foo.bar}";
        assertEquals("foo.bar", jsfAlias.substring(2, jsfAlias.length() - 1));
    }

    @Test
    public void findDomainAbility() {
        FooDomainAbility fooDomainAbility = InternalIndexer.findDomainAbility(FooDomainAbility.class);
        assertNotNull(fooDomainAbility);

        DomainAbilityDef domainAbilityDef = new DomainAbilityDef();
        try {
            domainAbilityDef.registerBean(fooDomainAbility);
            fail();
        } catch (BootstrapException expected) {
            assertTrue(expected.getMessage().startsWith("duplicated domain ability:"));
        }

        // 它没有加DomainAbility注解，是无法找到的
        IllegalDomainAbility illegalDomainAbility = InternalIndexer.findDomainAbility(IllegalDomainAbility.class);
        assertNull(illegalDomainAbility);
    }

    @Test
    public void noImplementationExt() {
        NotImplementedAbility ability = DDD.findAbility(NotImplementedAbility.class);
        assertNotNull(ability);
        ability.ping(fooModel);
    }

    @Test
    public void noImplementationExtAndNoDefaultExt() {
        NotImplementedAbility1 ability = DDD.findAbility(NotImplementedAbility1.class);
        assertNotNull(ability);
        ability.ping(fooModel);
    }

    @Test
    public void reducerFirstOf() {
        BarDomainAbility ability = DDD.findAbility(BarDomainAbility.class);
        String result = ability.submit(fooModel);
        // submit里执行了Reducer.firstOf，对应的扩展点是：BarExt, ProjectExt
        // 应该返回ProjectExt的结果
        assertEquals("2", result);
    }

    @Test
    public void reducerAll() {
        BarDomainAbility ability = DDD.findAbility(BarDomainAbility.class);
        String result = ability.submit2(fooModel);
        log.info("{}", result);
        // 由于submit2里执行了Reducer.all，返回值被置为null了
        assertNull(result);
    }

    @Test
    public void findDomainSteps() {
        List<String> codes = new ArrayList<>();
        codes.add(Steps.Submit.FooStep);
        codes.add(Steps.Submit.BarStep);
        codes.add(Steps.Cancel.EggStep); // 它属于CancelStep，而不属于SubmitStep
        List<StepDef> stepDefs = InternalIndexer.findDomainSteps(Steps.Submit.Activity, codes);
        assertEquals(2, stepDefs.size());
        assertEquals("foo活动", stepDefs.get(0).getName());
    }

    @Test
    public void wronglyFetchActivityCase() {
        // FooStep -> SubmitStep
        // EggStep -> CancelStep
        // 故意写错
        try {
            SubmitStep theStep = DDD.getStep(Steps.Cancel.Activity, Steps.Cancel.EggStep);
            fail();
        } catch (java.lang.ClassCastException expected) {
            // org.ddd.cp.ddd.runtime.registry.mock.step.EggStep$$EnhancerBySpringCGLIB$$21d4da4f cannot be cast to org.ddd.cp.ddd.runtime.registry.mock.step.SubmitStep
        }

        EggStep eggStep = DDD.getStep(Steps.Cancel.Activity, Steps.Cancel.EggStep);
        eggStep.execute(fooModel);
    }

    @Test
    public void dddFindSteps() {
        List<String> codes = new ArrayList<>();
        codes.add(Steps.Submit.FooStep);
        codes.add(Steps.Submit.BarStep);
        codes.add(Steps.Cancel.EggStep);
        List<SubmitStep> activities = DDD.findSteps(Steps.Submit.Activity, codes);
        assertEquals(2, activities.size());

        SubmitStep barStep = DDD.getStep(Steps.Submit.Activity, Steps.Submit.BarStep);
        assertNotNull(barStep);
        assertTrue(barStep instanceof BarStep);
        assertSame(activities.get(1), barStep);

        FooModel model = new FooModel();
        model.setProjectCode("unit test");
        log.info("will execute steps...");
        for (SubmitStep step : activities) {
            step.execute(model);
        }
    }

    @Test
    public void dddFindInvalidStep() {
        assertNull(DDD.getStep(Steps.Submit.Activity, "invalid-step-code"));
    }

    @Test
    public void abilityThrowsException() {
        try {
            BarDomainAbility ability = DDD.findAbility(BarDomainAbility.class);
            ability.throwsEx(fooModel);
        } catch (RuntimeException expected) {

        }
    }

    @Test
    public void integrationTest() {
        // domain service -> domain ability -> extension
        // ProjectExt
        fooDomainService.submitOrder(fooModel);
    }

    @Test(expected = RuntimeException.class)
    public void extThrowException() {
        // B2BExt
        fooModel.setProjectCode("");
        fooModel.setB2c(false);
        fooDomainService.submitOrder(fooModel);
    }

    @Test
    public void callExtTimeout() {
        // B2BExt
        fooModel.setProjectCode("");
        fooModel.setB2c(false);
        fooModel.setWillSleepLong(true);
        try {
            fooDomainService.submitOrder(fooModel);
            fail();
        } catch (ExtTimeoutException expected) {
            assertEquals("timeout:5ms", expected.getMessage());
        }
    }

    // Ext和JSF方式的超时机制叠加场景：只使用JSF的超时机制，Ext的超时被忽略
    @Test
    public void extTimeoutMixedWithJsfTimeout() {
        fooModel.setProjectCode("");
        fooModel.setB2c(true); // BarExt
        fooModel.setWillSleepLong(true);
        DDD.findAbility(FooDomainAbility.class).submit(fooModel); // 会走JSF的超时机制，测试用例的实现没有超时，因此不会产生ExtTimeoutException
    }

    @Test(expected = RuntimeException.class)
    public void callExtWithTimeoutAndThrownException() {
        // B2BExt
        fooModel.setProjectCode("");
        fooModel.setB2c(false);
        fooModel.setWillSleepLong(true);
        fooModel.setWillThrowRuntimeException(true);
        fooDomainService.submitOrder(fooModel);
    }

    @Test
    public void defaultExtensionComponent() {
        assertEquals(198, DDD.findAbility(BazAbility.class).guess(fooModel).intValue());
    }

    @Test
    public void patterPriority() {
        // IMultiMatchExt在B2BPattern、FooPattern上都有实现，而B2BPattern的priority最小，因此应该返回它的实例
        fooModel.setProjectCode("foo"); // 匹配 FooPattern
        fooModel.setB2c(false); // 匹配 B2BPattern
        List<ExtensionDef> extensions = InternalIndexer.findEffectiveExtensions(IMultiMatchExt.class, fooModel, true);
        assertEquals(1, extensions.size());
        assertEquals(B2BMultiMatchExt.class, extensions.get(0).getExtensionBean().getClass());
    }

    @Test
    public void decideSteps() {
        // fooModel不是B2B模式，匹配不了B2BDecideStepsExt
        assertNull(DDD.findAbility(DecideStepsAbility.class).decideSteps(fooModel, Steps.Submit.Activity));

        fooModel.setB2c(false);
        // B2BDecideStepsExt
        List<String> b2bSubmitSteps = DDD.findAbility(DecideStepsAbility.class).decideSteps(fooModel, Steps.Submit.Activity);
        assertEquals(2, b2bSubmitSteps.size());
    }

    @Test
    public void javaWhileLoop() {
        int loops = 0;
        while (++loops < 10) {
        }
        assertEquals(10, loops);
    }

    @Test
    public void stepsExecTemplate() {
        fooModel.setB2c(false);
        fooModel.setRedecide(true);
        fooModel.setStepsRevised(false);
        List<String> steps = DDD.findAbility(DecideStepsAbility.class).decideSteps(fooModel, Steps.Submit.Activity);
        // B2BDecideStepsExt: FooStep -> BarStep(if redecide then add Baz & Ham) -> BazStep -> HamStep
        submitStepsExec.execute(Steps.Submit.Activity, steps, fooModel);
        assertTrue(fooModel.isStepsRevised());
    }

}