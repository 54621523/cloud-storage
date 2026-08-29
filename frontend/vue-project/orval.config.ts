// orval.config.ts
import { defineConfig } from 'orval';

export default defineConfig({
    'share-api': {
        input: {
            target: './merged-api-docs.json',
        },
        // 输出配置：控制生成代码的细节
        output: {
            client: 'vue-query',
            mode: 'tags',
            target: './src/api/client.ts',
            schemas: './src/api/models',
            httpClient: 'axios',
            clean: true,
            mock: false,
            override: {
                mutator: {
                    path: './src/utils/matutor/custom-instance.ts',
                    name: 'customInstance',
                }
            },
        },
    }
});