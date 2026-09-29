package techit.lagmonitor.ccprofiler;

import java.util.Map;

import cpw.mods.fml.relauncher.IFMLLoadingPlugin;

/**
 * Lag Monitor CC Profiler: an optional, server-side add-on that measures ComputerCraft 1.63's
 * computer thread. Only does anything when the server is started with -Dcc.profileSeconds=N.
 * See Transformer.
 */
@IFMLLoadingPlugin.Name("LagMonitorCCProfiler")
@IFMLLoadingPlugin.MCVersion("1.6.4")
public class LoadingPlugin implements IFMLLoadingPlugin {
    @Deprecated
    public String[] getLibraryRequestClass() {
        return null;
    }

    public String[] getASMTransformerClass() {
        return new String[] {"techit.lagmonitor.ccprofiler.Transformer"};
    }

    public String getModContainerClass() {
        return null;
    }

    public String getSetupClass() {
        return null;
    }

    public void injectData(Map<String, Object> data) {
    }
}
