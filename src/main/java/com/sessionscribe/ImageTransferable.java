package com.sessionscribe;

import java.awt.Image;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;

/** Minimal {@link Transferable} that places an image on the system clipboard. */
class ImageTransferable implements Transferable
{
	private final Image image;

	ImageTransferable(Image image)
	{
		this.image = image;
	}

	@Override
	public DataFlavor[] getTransferDataFlavors()
	{
		return new DataFlavor[]{DataFlavor.imageFlavor};
	}

	@Override
	public boolean isDataFlavorSupported(DataFlavor flavor)
	{
		return DataFlavor.imageFlavor.equals(flavor);
	}

	@Override
	public Object getTransferData(DataFlavor flavor) throws UnsupportedFlavorException
	{
		if (!DataFlavor.imageFlavor.equals(flavor))
		{
			throw new UnsupportedFlavorException(flavor);
		}
		return image;
	}
}
