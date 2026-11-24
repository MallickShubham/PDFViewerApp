package com.example.pdfviewerapp;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Button;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

/**
 * Home screen. Lets the user pick a PDF and then opens it in PdfActivity.
 */
public class MainActivity extends AppCompatActivity {

    // Receives the result of the document picker
    private final ActivityResultLauncher<Intent> pdfPickerLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                // User pressed back / cancelled the picker
                if (result.getResultCode() != RESULT_OK) {
                    Toast.makeText(this, R.string.no_file_selected, Toast.LENGTH_SHORT).show();
                    return;
                }
                Intent data = result.getData();
                Uri uri = (data != null) ? data.getData() : null;
                if (uri == null) {
                    Toast.makeText(this, R.string.no_file_selected, Toast.LENGTH_SHORT).show();
                    return;
                }
                openPdf(uri);
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Button openPdfButton = findViewById(R.id.openPdfButton);
        openPdfButton.setOnClickListener(v -> launchPicker());
    }

    /** Opens the Storage Access Framework picker, showing only PDF files. */
    private void launchPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/pdf");
        try {
            pdfPickerLauncher.launch(intent);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.picker_error, Toast.LENGTH_LONG).show();
        }
    }

    /** Sends the selected PDF's URI to PdfActivity. */
    private void openPdf(Uri uri) {
        Intent intent = new Intent(this, PdfActivity.class);
        intent.setData(uri);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(intent);
    }
}
