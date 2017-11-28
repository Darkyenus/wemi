package game;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl.LwjglApplication;
import com.badlogic.gdx.backends.lwjgl.LwjglApplicationConfiguration;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.PerspectiveCamera;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.utils.viewport.ScreenViewport;

public class Main extends ApplicationAdapter {

    private ScreenViewport viewport;
    private ShapeRenderer shapeRenderer;

    @Override
    public void create() {
        viewport = new ScreenViewport(new PerspectiveCamera(90, Gdx.graphics.getWidth(), Gdx.graphics.getHeight()));
        shapeRenderer = new ShapeRenderer();
    }

    @Override
    public void render() {
        Gdx.gl.glClearColor(0.5f, 0.5f, 0.5f, 1.0f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT | GL20.GL_DEPTH_BUFFER_BIT);

        shapeRenderer.setProjectionMatrix(viewport.getCamera().combined);
        viewport.apply();
        final Vector2 mouse = new Vector2(Gdx.input.getX(), Gdx.input.getY());
        viewport.unproject(mouse);

        shapeRenderer.setColor(mouse.x / Gdx.graphics.getWidth(), mouse.y / Gdx.graphics.getWidth(), 1.0f, 1.0f);
        shapeRenderer.begin(ShapeRenderer.ShapeType.Filled);
        shapeRenderer.box(mouse.x-10, mouse.y-10, -50, 20, 20, 80);
        shapeRenderer.end();
    }

    @Override
    public void resize(int width, int height) {
        viewport.update(width, height, true);
    }

    @Override
    public void dispose() {
        shapeRenderer.dispose();
    }

    public static void main(String[] args) {
        LwjglApplicationConfiguration config = new LwjglApplicationConfiguration();
        config.width = 800;
        config.height = 600;
        config.vSyncEnabled = true;

        new LwjglApplication(new Main(), config);
    }
}
