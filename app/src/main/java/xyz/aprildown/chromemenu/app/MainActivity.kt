package xyz.aprildown.chromemenu.app

import android.os.Bundle
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlinx.android.synthetic.main.activity_main.*
import xyz.aprildown.chromemenu.AbstractAppMenuPropertiesDelegate
import xyz.aprildown.chromemenu.AppMenuButtonHelper
import xyz.aprildown.chromemenu.AppMenuHandler

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        textView.setOnTouchListener(
            AppMenuButtonHelper(
                AppMenuHandler(this, AbstractAppMenuPropertiesDelegate(), R.menu.menu)
            )
        )
    }

    override fun onOptionsItemSelected(item: MenuItem?): Boolean {
        Toast.makeText(this, item?.title?.toString(), Toast.LENGTH_SHORT).show()
        return true
    }
}