import java.util.List;

import com.alibaba.csp.sentinel.Entry;
import com.alibaba.csp.sentinel.EntryType;
import com.alibaba.csp.sentinel.SphU;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.alibaba.csp.sentinel.slots.block.flow.param.ParamFlowException;
import com.alibaba.csp.sentinel.slots.block.flow.param.ParamFlowItem;
import com.alibaba.csp.sentinel.slots.block.flow.param.ParamFlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.param.ParamFlowRuleManager;

public class ProductHotspotDemo {
    private static final String RESOURCE = "inventory.lookup";

    public static void main(String[] args) throws BlockException {
        ParamFlowItem featuredProduct = new ParamFlowItem()
                .setObject("sku-featured")
                .setClassType(String.class.getName())
                .setCount(2);
        ParamFlowRule rule = new ParamFlowRule(RESOURCE)
                .setParamIdx(0)
                .setDurationInSec(60)
                .setCount(1)
                .setParamFlowItemList(List.of(featuredProduct));
        ParamFlowRuleManager.loadRules(List.of(rule));

        verifyLookup("sku-hot", true);
        verifyLookup("sku-hot", false);
        verifyLookup("sku-other", true);
        verifyLookup("sku-featured", true);
        verifyLookup("sku-featured", true);
        verifyLookup("sku-featured", false);
    }

    private static void verifyLookup(String productId, boolean expectedAllowed) throws BlockException {
        boolean allowed;
        try {
            Entry entry = SphU.entry(RESOURCE, EntryType.IN, 1, productId);
            try {
                // Substitute the real inventory lookup here.
                allowed = true;
            } finally {
                entry.exit(1, productId);
            }
        } catch (ParamFlowException blocked) {
            allowed = false;
        }

        if (allowed != expectedAllowed) {
            throw new AssertionError("Unexpected admission result for " + productId + ": " + allowed);
        }
        System.out.println(productId + (allowed ? " PASS" : " BLOCK"));
    }
}
