package de.skyengine.utils.math;

public class FBM {

    private FastNoiseLite noise;

    public FBM(int seed) {
        this.noise = new FastNoiseLite(seed);
    }

    public double GetNoise(float x, float z) {
        return this.noise.GetNoise(x, z);
    }

    public float fbmRidged(float x, float z) {
        // Initial values
        float value = 0.0f;
        float amplitude = 0.5f;

        value += amplitude * -Math.abs(GetNoise(x, z));
        x = x * 2.0F + 100;
        z *= 2.0F;
        amplitude *= 0.5F;

        value += amplitude * -Math.abs(GetNoise(x, z));
        x = x * 2.0F + 100;
        z *= 2.0F;
        amplitude *= 0.5F;

        value += amplitude * -Math.abs(GetNoise(x, z));
        x = x * 2.0F + 100;
        z *= 2.0F;
        amplitude *= 0.5F;

        value += amplitude * -Math.abs(GetNoise(x, z));
        x = x * 2.0F + 100;
        z *= 2.0F;
        amplitude *= 0.5F;

        value += amplitude * -Math.abs(GetNoise(x, z));
        x = x * 2.0F + 100;
        z *= 2.0F;
        amplitude *= 0.5F;
        return value;
    }

    public float fbmSimplex(float x, float z) {
        // Initial values
        float value = 0.0f;
        float amplitude = 0.5f;

        value += amplitude * GetNoise(x, z);
        x = x * 2.0F + 100;
        z *= 2.0F;
        amplitude *= 0.5F;

        value += amplitude * GetNoise(x, z);
        x = x * 2.0F + 100;
        z *= 2.0F;
        amplitude *= 0.5F;

        value += amplitude * GetNoise(x, z);
        x = x * 2.0F + 100;
        z *= 2.0F;
        amplitude *= 0.5F;

        value += amplitude * GetNoise(x, z);
        x = x * 2.0F + 100;
        z *= 2.0F;
        amplitude *= 0.5F;
        return value;
    }
}
