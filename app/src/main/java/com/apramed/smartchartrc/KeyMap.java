package com.apramed.smartchartrc;

import android.view.KeyEvent;

/**
 * Tecla enviada à TV por cada botão (códigos KEYCODE do Android).
 * O SmartChart recebe exatamente como se fosse uma tecla do controle/teclado.
 * Para mudar o que um botão faz, troque só o valor aqui.
 */
public final class KeyMap {
    private KeyMap() {}

    // Testes
    public static final int C_LANDOLT = KeyEvent.KEYCODE_2;
    public static final int E_SNELLEN = KeyEvent.KEYCODE_1;
    public static final int ETDRS     = KeyEvent.KEYCODE_0;
    public static final int LETRAS    = KeyEvent.KEYCODE_3;
    public static final int NUMEROS   = KeyEvent.KEYCODE_4;
    public static final int LEA       = KeyEvent.KEYCODE_5;
    public static final int PIKTOTEA  = KeyEvent.KEYCODE_6;
    public static final int ISHIHARA  = 97;
    public static final int RED_GREEN = KeyEvent.KEYCODE_8;
    public static final int LANCASTER = KeyEvent.KEYCODE_7;
    public static final int PONTO_FIX = KeyEvent.KEYCODE_MENU;
    public static final int GALERIA   = 96;
    public static final int SMARTBOX  = KeyEvent.KEYCODE_S;

    // D-pad (iguais ao controle remoto original)
    public static final int UP    = KeyEvent.KEYCODE_DPAD_UP;
    public static final int DOWN  = KeyEvent.KEYCODE_DPAD_DOWN;
    public static final int LEFT  = KeyEvent.KEYCODE_DPAD_LEFT;
    public static final int RIGHT = KeyEvent.KEYCODE_DPAD_RIGHT;
    public static final int OK    = KeyEvent.KEYCODE_DPAD_CENTER;
    public static final int BACK  = KeyEvent.KEYCODE_BACK;
}
