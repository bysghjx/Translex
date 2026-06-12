package top.iencand.translex.client;

import net.fabricmc.fabric.api.datagen.v1.DataGeneratorEntrypoint;
import net.fabricmc.fabric.api.datagen.v1.FabricDataGenerator;

/**
 * Fabric 数据生成器入口点。
 * 用于在开发环境中运行数据生成任务（如语言文件、模型定义等）。
 * 目前尚未注册具体的数据提供器，留作未来扩展。
 */
public class TranslexDataGenerator implements DataGeneratorEntrypoint {

    @Override
    public void onInitializeDataGenerator(FabricDataGenerator fabricDataGenerator) {
        // 创建数据生成包，后续可在此注册 Provider
        FabricDataGenerator.Pack pack = fabricDataGenerator.createPack();
    }
}
